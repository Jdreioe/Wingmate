import Foundation
import Shared

extension Error {
    /// The typed failure a shared facade threw, or nil for cancellation and non-Kotlin errors.
    var nativeFailure: NativeFailure? {
        (self as NSError).userInfo["KotlinException"] as? NativeFailure
    }

    /// True when the task or the operation itself was cancelled, so there is nothing to report.
    var isNativeCancellation: Bool {
        if self is CancellationError { return true }
        if nativeFailure?.kind == NativeFailureKind.cancelled { return true }
        return (self as NSError).userInfo["KotlinException"] is KotlinCancellationException
    }
}
