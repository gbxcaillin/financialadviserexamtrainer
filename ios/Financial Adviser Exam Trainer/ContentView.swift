import SwiftUI
import WebKit

struct WebView: UIViewRepresentable {
    func makeCoordinator() -> AppBridge {
        AppBridge()
    }

    func makeUIView(context: Context) -> WKWebView {
        context.coordinator.makeWebView()
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        WebView()
            .edgesIgnoringSafeArea(.all)
    }
}
