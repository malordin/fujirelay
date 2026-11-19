package dev.malordin.fuji;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;

final class RunControl {
    private volatile boolean stopped;
    private volatile HttpURLConnection connection;
    boolean stopped() { return stopped; }
    void check() throws InterruptedIOException { if(stopped) throw new InterruptedIOException("Передача остановлена"); }
    void attach(HttpURLConnection value) throws InterruptedIOException { connection=value; if(stopped) { value.disconnect(); check(); } }
    void detach(HttpURLConnection value) { if(connection==value) connection=null; }
    void stop() {
        stopped=true;
        HttpURLConnection active=connection;
        // Disconnect on a separate thread: cancelling a socket must never block Android's UI.
        if(active!=null) new Thread(active::disconnect,"relay-disconnect").start();
    }
}
