// Stand-in TV apps with the real package names and labels, so the launcher's dock mapping,
// auto-pixelised icons and launch/back flow can be checked on an emulator. Never installed on the TV.
plugins {
    id("com.android.application")
}

android {
    namespace = "tv.dubi.stub"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }

    flavorDimensions += "app"
    productFlavors {
        fun stub(name: String, pkg: String) = create(name) {
            dimension = "app"
            applicationId = pkg
        }
        stub("youtube", "com.google.android.youtube.tv")
        stub("netflix", "com.netflix.ninja")
        stub("fptplay", "net.fptplay.ottbox")
        stub("vieon", "com.vieon.tv")
        stub("spotify", "com.spotify.tv.android")
        stub("vtvgo", "vn.vtv.vtvgo")
        stub("hdviet", "com.hdviet.tv")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
