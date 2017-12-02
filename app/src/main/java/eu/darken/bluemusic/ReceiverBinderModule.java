package eu.darken.bluemusic;

import android.content.BroadcastReceiver;

import dagger.Binds;
import dagger.Module;
import dagger.android.AndroidInjector;
import dagger.android.BroadcastReceiverKey;
import dagger.multibindings.IntoMap;
import eu.darken.bluemusic.core.bluetooth.BluetoothEventReceiver;
import eu.darken.bluemusic.core.bluetooth.BootCheckReceiver;
import eu.darken.bluemusic.core.bluetooth.BootCheckReceiverComponent;
import eu.darken.bluemusic.core.bluetooth.EventReceiverComponent;

@Module(subcomponents = {
        EventReceiverComponent.class,
        BootCheckReceiverComponent.class
})
abstract class ReceiverBinderModule {

    @Binds
    @IntoMap
    @BroadcastReceiverKey(BluetoothEventReceiver.class)
    abstract AndroidInjector.Factory<? extends BroadcastReceiver> eventReceiver(EventReceiverComponent.Builder impl);

    @Binds
    @IntoMap
    @BroadcastReceiverKey(BootCheckReceiver.class)
    abstract AndroidInjector.Factory<? extends BroadcastReceiver> bootReceiver(BootCheckReceiverComponent.Builder impl);
}