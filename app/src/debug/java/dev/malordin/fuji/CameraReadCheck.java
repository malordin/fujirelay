package dev.malordin.fuji;

import android.app.Instrumentation;
import android.hardware.usb.*;
import android.mtp.*;
import android.os.*;
import java.io.*;

/** Explicit hardware check: copies one JPEG temporarily, never uploads or deletes camera files. */
public final class CameraReadCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();MtpDevice camera=null;File partial=null,reference=null;
        try{
            UsbManager manager=getTargetContext().getSystemService(UsbManager.class);
            for(UsbDevice usb:manager.getDeviceList().values())if(usb.getVendorId()==0x04cb&&usb.getProductId()==0x02f3){
                if(!manager.hasPermission(usb))throw new IOException("Grant USB permission in FujiRelay first");
                camera=new MtpDevice(usb);if(!camera.open(manager.openDevice(usb)))throw new IOException("Cannot open PTP");break;
            }
            if(camera==null)throw new IOException("No camera");
            MtpObjectInfo info=null;
            outer:for(int storage:camera.getStorageIds())for(int handle:camera.getObjectHandles(storage,0,0)){
                MtpObjectInfo item=camera.getObjectInfo(handle);
                if(item!=null&&item.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")&&item.getCompressedSizeLong()>1024*1024&&item.getCompressedSizeLong()<32*1024*1024){info=item;break outer;}
            }
            if(info==null)throw new IOException("No suitable JPEG for bounded test");
            partial=File.createTempFile("partial-check",".jpg",getTargetContext().getCacheDir());
            reference=File.createTempFile("reference-check",".jpg",getTargetContext().getCacheDir());
            byte[] buffer=new byte[256*1024];long offset=0,start=SystemClock.elapsedRealtime();RunControl control=new RunControl();
            try(FileOutputStream out=new FileOutputStream(partial)){
                while(offset<info.getCompressedSizeLong()){
                    control.check();long count=camera.getPartialObject(info.getObjectHandle(),offset,Math.min(buffer.length,info.getCompressedSizeLong()-offset),buffer);
                    if(count<=0||count>buffer.length)throw new IOException("Partial read failed");out.write(buffer,0,(int)count);offset+=count;
                }
            }
            long elapsed=SystemClock.elapsedRealtime()-start;
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(reference,ParcelFileDescriptor.MODE_READ_WRITE)){
                if(!camera.importFile(info.getObjectHandle(),fd))throw new IOException("Reference read failed");
            }
            if(!RelayQueue.checksum(partial).equals(RelayQueue.checksum(reference)))throw new AssertionError("Chunked bytes differ from original");
            RunControl stop=new RunControl();long first=camera.getPartialObject(info.getObjectHandle(),0,buffer.length,buffer);stop.stop();
            try{stop.check();throw new AssertionError("Stop not honoured between USB blocks");}catch(java.io.InterruptedIOException expected){}
            if(first>=info.getCompressedSizeLong())throw new AssertionError("Expected incomplete read");
            result.putString("stream","\nPASS: real X-T30 II partial JPEG read matches native import hash; full-stop boundary after 256 KiB. "+offset+" bytes in "+elapsed+" ms. No upload.\n");finish(-1,result);
        }catch(Throwable e){result.putString("stream","\nFAIL: "+e+"\n");finish(1,result);}
        finally{if(camera!=null)camera.close();if(partial!=null)partial.delete();if(reference!=null)reference.delete();}
    }
}
