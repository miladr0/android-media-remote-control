import XCTest
@testable import MediaRemote

final class ProtocolDecodingTests: XCTestCase {
    func testDecodesMediaStatePayload() throws {
        let json = """
        {
          "v": 1,
          "type": "state",
          "id": "evt-12345678",
          "seq": 4,
          "ts": 1760000000000,
          "data": {
            "deviceId": "and-xiaomi14",
            "deviceName": "Xiaomi 14",
            "sessionId": "com.spotify.music:1001",
            "packageName": "com.spotify.music",
            "appName": "Spotify",
            "title": "Midnight Signal",
            "artist": "Kiasmos",
            "album": "Blurred",
            "durationMs": 245000,
            "positionMs": 12000,
            "playbackState": "PLAYING",
            "supportedActions": ["play", "pause", "next", "previous"],
            "artworkBase64": null,
            "artworkHash": null,
            "volume": { "current": 9, "max": 15, "percent": 60, "isFixed": false },
            "sessions": [
              {
                "sessionId": "com.spotify.music:1001",
                "packageName": "com.spotify.music",
                "appName": "Spotify",
                "title": "Midnight Signal",
                "playbackState": "PLAYING",
                "isSelected": true
              }
            ]
          }
        }
        """
        let env = try JSONDecoder().decode(ProtocolEnvelope<MediaStatePayload>.self, from: Data(json.utf8))
        XCTAssertEqual(env.data.appName, "Spotify")
        XCTAssertEqual(env.data.playbackState, "PLAYING")
        XCTAssertFalse(env.data.supportedActions.contains("stop"))
    }
}
