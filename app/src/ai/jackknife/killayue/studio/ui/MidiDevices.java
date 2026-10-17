package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.MidiInput;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

/** A screen-scoped MIDI input. All decoded events reach the UI thread, the engine's single producer. */
final class MidiDevices implements AutoCloseable {
    interface Target extends MidiInput.Target {
        void allOff();
    }
    private final Activity a;
    private final Target target;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MidiManager manager;
    private MidiDevice device;
    private MidiOutputPort port;
    private int generation;
    private boolean closed;
    private BluetoothLeScanner scanner;
    private ScanCallback scan;
    private AlertDialog picker;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean overflow = new java.util.concurrent.atomic.AtomicBoolean();
    private final android.app.Application.ActivityLifecycleCallbacks lifecycle =
        new android.app.Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, android.os.Bundle state) {}
            public void onActivityStarted(Activity activity) {}
            public void onActivityResumed(Activity activity) {}
            public void onActivityPaused(Activity activity) {
                if (activity == a)
                    target.allOff();
            }
            public void onActivityStopped(Activity activity) {
                if (activity == a) {
                    stopScan();
                    if (picker != null)
                        picker.dismiss();
                    disconnect();
                }
            }
            public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) {}
            public void onActivityDestroyed(Activity activity) {
                if (activity == a)
                    close();
            }
        };
    private void dispatch(final int token, final Runnable action) {
        if (pending.incrementAndGet() > 512) {
            pending.decrementAndGet();
            if (overflow.compareAndSet(false, true))
                handler.post(new Runnable() {
                    public void run() {
                        overflow.set(false);
                        if (!closed && token == generation)
                            target.allOff();
                    }
                });
            return;
        }
        handler.post(new Runnable() {
            public void run() {
                try {
                    if (!closed && token == generation)
                        action.run();
                } finally {
                    pending.decrementAndGet();
                }
            }
        });
    }
    private final MidiManager.DeviceCallback deviceCallback = new MidiManager.DeviceCallback() {
        public void onDeviceRemoved(MidiDeviceInfo info) {
            if (device != null && device.getInfo().getId() == info.getId()) {
                disconnect();
                Ui.toast(a, "MIDI disconnected.");
            }
        }
    };
    MidiDevices(Activity a, Target target) {
        this.a = a;
        this.target = target;
        a.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        manager = (MidiManager) a.getSystemService(Activity.MIDI_SERVICE);
        if (manager != null)
            manager.registerDeviceCallback(deviceCallback, handler);
    }
    void show() {
        if (closed)
            return;
        if (manager == null) {
            Ui.toast(a, "This device has no MIDI service.");
            return;
        }
        new Lux.Builder(a)
            .setTitle("MIDI keyboard")
            .setItems(new String[] {"USB / connected MIDI", "Find Bluetooth MIDI", "Disconnect"},
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0)
                            usb();
                        else if (which == 1)
                            bluetooth();
                        else
                            disconnect();
                    }
                })
            .show();
    }
    private void usb() {
        final ArrayList<MidiDeviceInfo> devices = new ArrayList<MidiDeviceInfo>();
        final ArrayList<Integer> ports = new ArrayList<Integer>();
        ArrayList<String> labels = new ArrayList<String>();
        for (MidiDeviceInfo info : manager.getDevices())
            for (MidiDeviceInfo.PortInfo p : info.getPorts())
                if (p.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
                    devices.add(info);
                    ports.add(p.getPortNumber());
                    labels.add(info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME, "MIDI device") + " · "
                        + p.getName() + " " + (p.getPortNumber() + 1));
                }
        if (devices.isEmpty()) {
            Ui.toast(a, "Connect a USB MIDI keyboard, then open this list again.");
            return;
        }
        new Lux.Builder(a)
            .setTitle("MIDI input port")
            .setItems(labels.toArray(new String[0]),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        disconnect();
                        final int token = generation;
                        manager.openDevice(devices.get(which), listener(token, ports.get(which)), handler);
                    }
                })
            .show();
    }
    private MidiManager.OnDeviceOpenedListener listener(final int token, final int number) {
        return new MidiManager.OnDeviceOpenedListener() {
            public void onDeviceOpened(MidiDevice opened) {
                if (closed || token != generation) {
                    quiet(opened);
                    return;
                }
                if (opened == null) {
                    Ui.toast(a, "Could not open that MIDI keyboard.");
                    return;
                }
                MidiOutputPort output = opened.openOutputPort(number);
                if (output == null) {
                    quiet(opened);
                    Ui.toast(a, "That keyboard has no available output port.");
                    return;
                }
                device = opened;
                port = output;
                final MidiInput parser = new MidiInput(new MidiInput.Target() {
                    public void note(final int ch, final int key, final int velocity, final boolean on) {
                        dispatch(token, new Runnable() {
                            public void run() {
                                target.note(ch, key, velocity, on);
                            }
                        });
                    }
                    public void control(final int ch, final int controller, final int value) {
                        dispatch(token, new Runnable() {
                            public void run() {
                                target.control(ch, controller, value);
                            }
                        });
                    }
                });
                output.connect(new MidiReceiver() {
                    public synchronized void onSend(byte[] data, int offset, int count, long timestamp) {
                        parser.receive(data, offset, count);
                    }
                    public synchronized void onFlush() {
                        parser.reset();
                        dispatch(token, new Runnable() {
                            public void run() {
                                target.allOff();
                            }
                        });
                    }
                });
                Ui.toast(a, "MIDI keyboard connected.");
            }
        };
    }
    private void bluetooth() {
        ArrayList<String> missing = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31 && a.getApplicationInfo().targetSdkVersion >= 31) {
            for (String permission :
                new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
                if (a.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                    missing.add(permission);
        } else if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!missing.isEmpty()) {
            a.requestPermissions(missing.toArray(new String[0]), 782);
            Ui.toast(a, "Allow access, then choose Find Bluetooth MIDI again.");
            return;
        }
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Activity.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Ui.toast(a, "Turn Bluetooth on, then try again.");
            return;
        }
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Ui.toast(a, "Bluetooth scanning is unavailable.");
            return;
        }
        final ArrayList<BluetoothDevice> found = new ArrayList<BluetoothDevice>();
        final ArrayAdapter<String> labels = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_1);
        ListView list = new ListView(a);
        list.setAdapter(labels);
        picker = new Lux.Builder(a)
                     .setTitle("Bluetooth MIDI · scanning for 10 seconds")
                     .setView(list)
                     .setNegativeButton("Close", null)
                     .create();
        picker.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                stopScan();
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, android.view.View v, int position, long id) {
                BluetoothDevice chosen = found.get(position);
                picker.dismiss();
                disconnect();
                try {
                    manager.openBluetoothDevice(chosen, listener(generation, 0), handler);
                } catch (SecurityException ex) {
                    Ui.toast(a, "Bluetooth access was denied.");
                }
            }
        });
        picker.show();
        scan = new ScanCallback() {
            public void onScanResult(int type, final ScanResult result) {
                final ScanCallback owner = this;
                handler.post(new Runnable() {
                    public void run() {
                        if (scan != owner || closed)
                            return;
                        BluetoothDevice d = result.getDevice();
                        if (!found.contains(d)) {
                            found.add(d);
                            String name;
                            try {
                                name = d.getName();
                            } catch (SecurityException ex) {
                                name = null;
                            }
                            labels.add(name == null ? "MIDI keyboard " + found.size() : name);
                        }
                    }
                });
            }
            public void onScanFailed(int code) {
                final ScanCallback owner = this;
                handler.post(new Runnable() {
                    public void run() {
                        if (scan != owner)
                            return;
                        stopScan();
                        if (picker != null)
                            picker.setTitle("Bluetooth scan failed (" + code + ")");
                    }
                });
            }
        };
        try {
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
                                      .setServiceUuid(ParcelUuid.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700"))
                                      .build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
        } catch (SecurityException ex) {
            stopScan();
            picker.dismiss();
            Ui.toast(a, "Bluetooth access was denied.");
            return;
        }
        final ScanCallback started = scan;
        handler.postDelayed(new Runnable() {
            public void run() {
                if (scan == started) {
                    stopScan();
                    if (picker != null)
                        picker.setTitle(found.isEmpty() ? "No MIDI keyboards found · enable pairing and try again"
                                                        : "Choose a Bluetooth MIDI keyboard");
                }
            }
        }, 10000);
    }
    private void stopScan() {
        if (scanner != null && scan != null)
            try {
                scanner.stopScan(scan);
            } catch (SecurityException ignored) {
            }
        scan = null;
        scanner = null;
    }
    private static void quiet(java.io.Closeable item) {
        if (item != null)
            try {
                item.close();
            } catch (IOException ignored) {
            }
    }
    void disconnect() {
        generation++;
        quiet(port);
        quiet(device);
        port = null;
        device = null;
        target.allOff();
    }
    public void close() {
        if (closed)
            return;
        closed = true;
        a.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        stopScan();
        if (picker != null)
            picker.dismiss();
        if (manager != null)
            manager.unregisterDeviceCallback(deviceCallback);
        disconnect();
        handler.removeCallbacksAndMessages(null);
    }
}
