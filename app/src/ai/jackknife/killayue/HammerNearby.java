package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.content.Context;
import android.net.nsd.*;
import android.os.*;
import java.io.Closeable;
import java.util.*;

/** LAN discovery supplies candidate pins; TLS must prove possession before any music is accepted. */
final class HammerNearby implements Closeable {
    interface Found {
        void peer(Hammer.Card card);
    }
    private static final String TYPE = "_hammer._tcp.";
    private final NsdManager nsd;
    private final Found found;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<NsdServiceInfo>();
    private final Set<String> names = new HashSet<String>();
    private boolean closed, resolving, started;
    private final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String t) {}
        public void onDiscoveryStopped(String t) {}
        public void onStartDiscoveryFailed(String t, int e) { close(); }
        public void onStopDiscoveryFailed(String t, int e) {}
        public void onServiceLost(NsdServiceInfo info) {}
        public void onServiceFound(final NsdServiceInfo info) {
            main.post(new Runnable() {
                public void run() {
                    if (closed || names.size() >= 64 || !names.add(info.getServiceName()))
                        return;
                    pending.add(info);
                    resolve();
                }
            });
        }
    };
    HammerNearby(Context c, Found found) {
        nsd = (NsdManager)c.getSystemService(Context.NSD_SERVICE);
        this.found = found;
    }
    void start() {
        try {
            started = true;
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            close();
        }
    }
    private void resolve() {
        if (closed || resolving || pending.isEmpty())
            return;
        resolving = true;
        try {
            nsd.resolveService(pending.remove(), new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo info, int error) { next(); }
                public void onServiceResolved(NsdServiceInfo info) {
                    try {
                        byte[] raw = info.getAttributes().get("pin");
                        if (raw != null && raw.length == 64 && info.getHost() != null) {
                            String pin = new String(raw, "US-ASCII");
                            if (!pin.equals(HammerAndroid.pin()))
                                found.peer(new Hammer.Card(info.getHost().getHostAddress(), info.getPort(),
                                                           pin, ""));
                        }
                    } catch (Exception ignored) {
                    }
                    next();
                }
                private void next() {
                    main.post(new Runnable() {
                        public void run() {
                            resolving = false;
                            resolve();
                        }
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }
    public void close() {
        closed = true;
        pending.clear();
        if (started) {
            started = false;
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }
    }
    static final class Announcement implements Closeable {
        private final NsdManager nsd;
        private boolean registered;
        private final NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            public void onRegistrationFailed(NsdServiceInfo i, int e) { registered = false; }
            public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
            public void onServiceRegistered(NsdServiceInfo i) {}
            public void onServiceUnregistered(NsdServiceInfo i) {}
        };
        Announcement(Context c, String pin, int port) {
            nsd = (NsdManager)c.getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("HAMMER-" + pin.substring(0, 12));
            info.setServiceType(TYPE);
            info.setPort(port);
            info.setAttribute("pin", pin);
            info.setAttribute("v", "1");
            try {
                registered = true;
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (Exception e) {
                registered = false;
            }
        }
        public void close() {
            if (registered) {
                registered = false;
                try {
                    nsd.unregisterService(listener);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
