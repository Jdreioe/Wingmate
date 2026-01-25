import SwiftUI
import Shared

struct PredictionBar: View {
    let result: Shared.PredictionResult
    let onWordSelected: (String) -> Void
    let onLetterSelected: (String) -> Void
    let fontSizeScale: Double
    
    // HIG-style constants
    private let barHeight: CGFloat = 44 // Standard input accessory height
    private let blurStyle: UIBlurEffect.Style = .systemChromeMaterial
    
    var body: some View {
        VStack(spacing: 0) {
            Divider()
            
            // Background with blur effect
            ZStack {
                VisualEffectView(effect: UIBlurEffect(style: blurStyle))
                    .ignoresSafeArea()
                
                HStack(spacing: 0) {
                    if !result.words.isEmpty {
                        // Word candidates (centered, typically 3)
                        let words = result.words.prefix(3)
                        ForEach(Array(words.enumerated()), id: \.element) { index, word in
                            if index > 0 {
                                Divider()
                                    .padding(.vertical, 8)
                            }
                            
                            Button(action: { onWordSelected(word) }) {
                                Text(word)
                                    .font(.system(size: 17 * fontSizeScale))
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    .contentShape(Rectangle()) // Make entire area tappable
                            }
                            .foregroundStyle(Color.primary)
                        }
                    } else if !result.letters.isEmpty {
                        // If no words, show letter predictions in a horizontal scroll
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(result.letters, id: \.self) { char in
                                    Button(action: { onLetterSelected(String(char)) }) {
                                        Text(String(char))
                                            .font(.system(size: 20 * fontSizeScale, weight: .medium))
                                            .frame(minWidth: 32, minHeight: 32)
                                            .background(Color(.secondarySystemFill))
                                            .clipShape(RoundedRectangle(cornerRadius: 6))
                                    }
                                    .foregroundStyle(Color.primary)
                                }
                            }
                            .padding(.horizontal, 8)
                        }
                    }
                }
            }
            .frame(height: barHeight)
        }
    }
}

// Helper for UIVisualEffectView
struct VisualEffectView: UIViewRepresentable {
    var effect: UIVisualEffect?
    
    func makeUIView(context: Context) -> UIVisualEffectView {
        UIVisualEffectView(effect: effect)
    }
    
    func updateUIView(_ uiView: UIVisualEffectView, context: Context) {
        uiView.effect = effect
    }
}
