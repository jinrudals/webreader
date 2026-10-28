package app.webreader

import android.app.Application

class WebReaderApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
