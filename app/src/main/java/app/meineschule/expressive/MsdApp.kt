package app.meineschule.expressive

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class MsdApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer; private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        app.meineschule.expressive.work.Notifier.ensureChannel(this)
    }

    /** Coil lädt Bilder über denselben OkHttp-Client → Bearer wird automatisch (nur für die eigene Origin) gesetzt. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).diskCache(null).components { add(OkHttpNetworkFetcherFactory(callFactory = { container.authedClient })) }.crossfade(true).build()
}
