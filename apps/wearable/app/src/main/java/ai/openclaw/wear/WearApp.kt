package ai.openclaw.wear

import android.app.Application

class WearApp : Application() {
    val phoneBridge by lazy { PhoneBridge(this) }
    val assetStore by lazy { WearAssetStore(this) }

    override fun onCreate() {
        super.onCreate()
        assetStore.start()
    }
}
