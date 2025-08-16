import SwiftUI
import Shared

struct LiquidGlassBackground: View {
    var body: some View {
        ZStack {
            LinearGradient(gradient: Gradient(colors: [Color.purple.opacity(0.4), Color.blue.opacity(0.4)]), startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()
            // Blur glass layer
            Color.white.opacity(0.15)
                .blur(radius: 30)
                .ignoresSafeArea()
            // Floating blobs
            Circle()
                .fill(Color.white.opacity(0.15))
                .frame(width: 220, height: 220)
                .offset(x: -140, y: -220)
                .blur(radius: 40)
            Circle()
                .fill(Color.white.opacity(0.12))
                .frame(width: 300, height: 300)
                .offset(x: 160, y: 240)
                .blur(radius: 50)
        }
    }
}

final class PhraseViewModel: ObservableObject {
    private let bridge = KoinBridge()
    @Published var items: [DomainPhrase] = []
    @Published var input: String = ""
    @Published var loading: Bool = false
    @Published var error: String? = nil

    func load() {
        loading = true
        error = nil
        Task {
            do {
                let list = try await bridge.listPhrases()
                await MainActor.run {
                    self.items = list.map { DomainPhrase(from: $0) }
                    self.loading = false
                }
            } catch {
                await MainActor.run { self.error = error.localizedDescription; self.loading = false }
            }
        }
    }

    func speak() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        Task { try? await bridge.speak(text: text) }
    }
}

struct DomainPhrase: Identifiable {
    let id: String
    let text: String
    let name: String?
    let isCategory: Bool
    init(from p: Shared.Phrase) {
        id = p.id
        text = p.text
        name = p.name
        isCategory = p.isCategory
    }
}

struct ContentView: View {
    @StateObject var vm = PhraseViewModel()

    var body: some View {
        ZStack {
            LiquidGlassBackground()

            VStack(spacing: 16) {
                // Top title in glass card
                Text("Wingmate iOS")
                    .font(.system(size: 32, weight: .bold))
                    .foregroundStyle(.white)
                    .shadow(color: Color.black.opacity(0.3), radius: 6, x: 0, y: 2)

                glassCard {
                    HStack(spacing: 8) {
                        TextField("Enter text to speak", text: $vm.input)
                            .textFieldStyle(.roundedBorder)
                        Button(action: vm.speak) {
                            Image(systemName: "waveform")
                                .font(.system(size: 18, weight: .semibold))
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }

                glassCard {
                    if vm.loading { ProgressView().progressViewStyle(.circular) } else {
                        if let err = vm.error { Text("Error: \(err)") } else {
                            ScrollView {
                                LazyVStack(alignment: .leading, spacing: 12) {
                                    ForEach(vm.items) { p in
                                        if !p.isCategory {
                                            HStack {
                                                Text(p.name ?? p.text).lineLimit(2)
                                                Spacer()
                                                Button {
                                                    Task { try? await KoinBridge().speak(text: p.text) }
                                                } label: { Image(systemName: "play.fill") }
                                                .buttonStyle(.bordered)
                                            }
                                            .padding(12)
                                            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(20)
        }
        .onAppear { vm.load() }
    }

    @ViewBuilder
    private func glassCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .shadow(color: Color.black.opacity(0.2), radius: 10, x: 0, y: 4)
            )
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View { ContentView() }
}
