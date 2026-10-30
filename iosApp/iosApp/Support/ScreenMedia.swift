import SwiftUI
import UIKit
import Shared

/// Local and embedded images work offline; unavailable media leaves the Page color visible.
func loadScreenImage(_ source: String?) async -> UIImage? {
    guard let source else { return nil }
    if source.hasPrefix("data:"), let comma = source.firstIndex(of: ",") {
        let encoded = String(source[source.index(after: comma)...])
        return await Task.detached {
            Data(base64Encoded: encoded).flatMap(UIImage.init(data:))
        }.value
    }
    guard let url = URL(string: source) else { return nil }
    if url.isFileURL {
        return await Task.detached { (try? Data(contentsOf: url)).flatMap(UIImage.init(data:)) }.value
    }
    guard url.scheme == "https" || url.scheme == "http",
          let (data, response) = try? await URLSession.shared.data(for: URLRequest(url: url, timeoutInterval: 10)),
          let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else { return nil }
    return UIImage(data: data)
}

struct PageBackgroundView: View {
    let source: String?
    let fit: String
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geometry in
            if let image {
                Image(uiImage: image).resizable()
                    .aspectRatio(contentMode: fit == "Fit" ? .fit : .fill)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
            }
        }
        .task(id: source) { image = nil; image = await loadScreenImage(source) }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct ScreenPdfShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

@MainActor
func exportScreenPdf(id: String) async throws -> URL {
    let pages = try await IosDiBridge().boardsFacade().printedPages(boardSetId: id, locale: Locale.current.identifier)
    var images: [String: UIImage] = [:]
    var attempted: Set<String> = []
    for page in pages {
        for cell in page.cells {
            guard let image = cell.image else { continue }
            let source = image.dataUrl ?? image.data.map { "data:\(image.contentType ?? "image/png");base64,\($0)" } ?? image.url
            if let source, attempted.insert(source).inserted { images[source] = await loadScreenImage(source) }
        }
    }
    let bounds = CGRect(x: 0, y: 0, width: 842, height: 595)
    let loadedImages = images
    let data = await Task.detached {
        let renderer = UIGraphicsPDFRenderer(bounds: bounds)
        return renderer.pdfData { context in
            for page in pages {
                context.beginPage()
                let title = "\(page.title) · \(page.number)"
                (title as NSString).draw(in: CGRect(x: 24, y: 16, width: 794, height: 32), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 20), .foregroundColor: UIColor.black])
                let width = 794 / CGFloat(max(1, page.columns))
                let height = 515 / CGFloat(max(1, page.rows))
                for cell in page.cells {
                    let rect = CGRect(x: 24 + CGFloat(cell.column) * width, y: 56 + CGFloat(cell.row) * height,
                                      width: CGFloat(cell.columnSpan) * width, height: CGFloat(cell.rowSpan) * height).insetBy(dx: 2, dy: 2)
                    let color = cell.backgroundColor.map { UIColor(Color(hex: $0)) } ?? .white
                    color.setFill()
                    UIBezierPath(rect: rect).fill()
                    UIColor.black.setStroke()
                    UIBezierPath(rect: rect).stroke()
                    var textRect = rect.insetBy(dx: 6, dy: 6)
                    if let image = cell.image {
                        let source = image.dataUrl ?? image.data.map { "data:\(image.contentType ?? "image/png");base64,\($0)" } ?? image.url
                        if let source, let bitmap = loadedImages[source] {
                            let area = CGRect(x: textRect.minX, y: textRect.minY, width: textRect.width, height: textRect.height * 0.6)
                            let scale = min(area.width / bitmap.size.width, area.height / bitmap.size.height)
                            let size = CGSize(width: bitmap.size.width * scale, height: bitmap.size.height * scale)
                            bitmap.draw(in: CGRect(x: area.midX - size.width / 2, y: area.midY - size.height / 2, width: size.width, height: size.height))
                            textRect.origin.y += area.height
                            textRect.size.height -= area.height
                        }
                    }
                    let link = cell.opens.map { String(format: NSLocalizedString("boardset.print_link", comment: ""), $0.title, Int($0.pageNumber)) }
                    let text = cell.label + (link.map { "\n" + $0 } ?? "")
                    let paragraph = NSMutableParagraphStyle()
                    paragraph.alignment = .center
                    // An opaque label backing preserves contrast over author colors.
                    UIColor.white.setFill()
                    UIBezierPath(rect: textRect).fill()
                    (text as NSString).draw(in: textRect, withAttributes: [.font: UIFont.systemFont(ofSize: min(16, max(8, textRect.height / 3))), .foregroundColor: UIColor.black, .paragraphStyle: paragraph])
                }
            }
        }
    }.value
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("Wingmate-\(UUID().uuidString).pdf")
    try await Task.detached { try data.write(to: url, options: .atomic) }.value
    return url
}
