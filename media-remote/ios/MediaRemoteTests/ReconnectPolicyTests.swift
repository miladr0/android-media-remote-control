import XCTest
@testable import MediaRemote

final class ReconnectPolicyTests: XCTestCase {
    func testExponentialBackoffCapsAtSixteenSeconds() {
        XCTAssertEqual(ReconnectPolicy.delaySeconds(forAttempt: 1), 1.0)
        XCTAssertEqual(ReconnectPolicy.delaySeconds(forAttempt: 2), 2.0)
        XCTAssertEqual(ReconnectPolicy.delaySeconds(forAttempt: 3), 4.0)
        XCTAssertEqual(ReconnectPolicy.delaySeconds(forAttempt: 4), 8.0)
        XCTAssertEqual(ReconnectPolicy.delaySeconds(forAttempt: 5), 16.0)
        XCTAssertEqual(ReconnectPolicy.delaySeconds(forAttempt: 8), 16.0)
    }
}
