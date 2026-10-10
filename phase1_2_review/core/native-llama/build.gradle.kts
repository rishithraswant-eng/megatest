plugins { id("com.android.library") }
android {
    namespace = "org.seedhecode.nativellama"
    compileSdk = 34
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 23
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared"; cppFlags += "-O3" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
