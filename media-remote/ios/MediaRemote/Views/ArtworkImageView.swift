import SwiftUI

struct ArtworkImageView: View {
    let base64: String?

    var body: some View {
        if let base64,
           let data = Data(base64Encoded: base64),
           let uiImage = UIImage(data: data) {
            Image(uiImage: uiImage)
                .resizable()
                .scaledToFill()
        } else {
            ZStack {
                Color.secondary.opacity(0.16)
                Image(systemName: "music.note")
                    .font(.system(size: 56))
                    .foregroundStyle(.secondary)
            }
        }
    }
}
