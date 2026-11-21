import XCTest
@testable import MediaRemote

final class ReplayGuardTests: XCTestCase {
    func testRejectsDuplicateSeqAndNonce() {
        let guardObj = InboundReplayGuard(windowMs: 30_000)
        let now: Int64 = 1_760_000_000_000

        XCTAssertTrue(guardObj.verify(seq: 1, ts: now, nonce: "n-1", nowMs: now))
        XCTAssertFalse(guardObj.verify(seq: 1, ts: now, nonce: "n-2", nowMs: now))
        XCTAssertFalse(guardObj.verify(seq: 2, ts: now, nonce: "n-1", nowMs: now))
        XCTAssertFalse(guardObj.verify(seq: 3, ts: now - 45_000, nonce: "n-3", nowMs: now))
        XCTAssertTrue(guardObj.verify(seq: 2, ts: now, nonce: "n-4", nowMs: now))
    }
}
