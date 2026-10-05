# Keep the @JavascriptInterface methods the WebView calls by name.
-keepclassmembers class com.gbxps.fasea.AppBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
