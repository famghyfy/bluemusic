package eu.darken.bluemusic.core.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import eu.darken.bluemusic.App;
import eu.darken.bluemusic.core.Settings;
import eu.darken.bluemusic.core.bluetooth.BluetoothEventReceiver;
import eu.darken.bluemusic.core.bluetooth.BluetoothSource;
import eu.darken.bluemusic.core.bluetooth.SourceDevice;
import eu.darken.bluemusic.core.database.DeviceManager;
import eu.darken.bluemusic.core.database.ManagedDevice;
import io.reactivex.Scheduler;
import io.reactivex.SingleObserver;
import io.reactivex.SingleSource;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;


public class BlueMusicService extends Service implements VolumeObserver.Callback {
    @Inject DeviceManager deviceManager;
    @Inject BluetoothSource bluetoothSource;
    @Inject StreamHelper streamHelper;
    @Inject VolumeObserver volumeObserver;
    @Inject Settings settings;
    @Inject ServiceHelper serviceHelper;
    @Inject List<ActionModule> actionModules;
    final Scheduler scheduler = Schedulers.from(Executors.newSingleThreadExecutor());
    private volatile boolean adjusting = false;

    @Override
    public void onCreate() {
        Timber.v("onCreate()");
        ((App) getApplication()).serviceInjector().inject(this);
        super.onCreate();
        if (settings.isVolumeChangeListenerEnabled()) {
            volumeObserver.addCallback(streamHelper.getMusicId(), this);
            volumeObserver.addCallback(streamHelper.getCallId(), this);
            getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volumeObserver);
        }
    }

    @Override
    public void onDestroy() {
        Timber.v("onDestroy()");
        serviceHelper.stopForeground();
        getContentResolver().unregisterContentObserver(volumeObserver);
        super.onDestroy();
    }

    public class MBinder extends Binder {

    }

    private final MBinder binder = new MBinder();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Timber.v("onUnbind(intent=%s)", intent);
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        Timber.v("onRebind(intent=%s)", intent);
        super.onRebind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Timber.v("onStartCommand(intent=%s, flags=%d, startId=%d)", intent, flags, startId);
        if (intent == null) {
            Timber.w("Intent was null");
            return START_STICKY;
        }
        serviceHelper.startForeground();

        SourceDevice.Event event = intent.getParcelableExtra(BluetoothEventReceiver.EXTRA_DEVICE_EVENT);
        if (event != null) {
            bluetoothSource.getConnectedDevices()
                    .delaySubscription(1000, TimeUnit.MILLISECONDS)
                    .subscribeOn(scheduler)
                    .map(deviceMap -> {
                        if (event.getType() == SourceDevice.Event.Type.CONNECTED && !deviceMap.containsKey(event.getAddress())) {
                            Thread.sleep(500);
                            throw new Exception("Device not yet fully connected.");
                        }
                        return event;
                    })
                    .retry(20)
                    .flatMap(new Function<SourceDevice.Event, SingleSource<ManagedDevice.Action>>() {
                        @Override
                        public SingleSource<ManagedDevice.Action> apply(SourceDevice.Event deviceEvent) throws Exception {
                            return deviceManager.loadDevices(true).map(deviceMap -> new ManagedDevice.Action(deviceMap.get(deviceEvent.getAddress()), deviceEvent.getType()));
                        }
                    })
                    .subscribe(new SingleObserver<ManagedDevice.Action>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            adjusting = true;
                            Timber.d("Incoming adjustment.");
                        }

                        @Override
                        public void onSuccess(ManagedDevice.Action value) {
                            Timber.d("Handling %s", event);
                            final ManagedDevice device = value.getDevice();
                            final CountDownLatch latch = new CountDownLatch(actionModules.size());
                            for (ActionModule module : actionModules) {
                                new Thread(() -> {
                                    Timber.d("Running module %s", module);
                                    module.handle(device, event);
                                    latch.countDown();
                                    Timber.d("Module %s finished", module);
                                }).start();
                            }
                            try {
                                latch.await();
                            } catch (InterruptedException e) { Timber.e(e); }

                            if (event.getType() == SourceDevice.Event.Type.DISCONNECTED) {
                                handleDisconnect();
                            }

                            adjusting = false;
                            Timber.d("Adjustment finished.");

                            if (!settings.isVolumeChangeListenerEnabled()) {
                                Timber.i("We don't want to listen to volume changes, stopping service.");
                                stopSelf();
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            adjusting = false;
                            Timber.e(e, null);
                        }
                    });
        }

        return START_STICKY;
    }

    private void handleDisconnect() {
        deviceManager.loadDevices(true)
                .subscribeOn(Schedulers.computation())
                .subscribe(deviceMap -> {
                    boolean stop = true;
                    for (ManagedDevice d : deviceMap.values()) {
                        if (d.isActive()) {
                            stop = false;
                            break;
                        }
                    }
                    if (stop) {
                        if (stop) Timber.i("No more active devices, stopping service.");
                        stopSelf();
                    }
                });
    }

    @Override
    public void onVolumeChanged(int streamId, int volume) {
        if (adjusting || streamHelper.wasUs(streamId, volume)) {
            Timber.v("Volume change was triggered by us, ignoring it.");
            return;
        }
        float percentage = streamHelper.getVolumePercentage(streamId);
        deviceManager.loadDevices(false)
                .map(deviceMap -> {
                    Collection<ManagedDevice> active = new HashSet<>();
                    for (ManagedDevice d : deviceMap.values()) {
                        if (d.isActive()) active.add(d);
                    }
                    return active;
                })
                .filter(managedDevices -> !managedDevices.isEmpty())
                .toFlowable()
                .flatMapIterable(managedDevices -> managedDevices)
                .map(device -> {
                    if (streamId == streamHelper.getCallId()) device.setCallVolume(percentage);
                    else device.setMusicVolume(percentage);
                    return device;
                })
                .toList()
                .subscribe(actives -> {
                    deviceManager.update(actives)
                            .subscribeOn(Schedulers.computation())
                            .subscribe();
                });
    }
}