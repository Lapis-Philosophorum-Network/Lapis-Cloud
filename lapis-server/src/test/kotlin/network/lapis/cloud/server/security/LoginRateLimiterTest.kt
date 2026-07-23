package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

/** Pure tests of [LoginRateLimiter] -- no DB access. */
class LoginRateLimiterTest :
    FunSpec({
        test("checkAllowed() is true for a never-seen key") {
            val limiter = LoginRateLimiter()
            limiter.checkAllowed("email:never-seen@example.org") shouldBe true
        }

        test("checkAllowed() stays true below maxFailures, becomes false at maxFailures") {
            val limiter = LoginRateLimiter(maxFailures = 3)
            val key = "email:hammered@example.org"

            limiter.recordFailure(key)
            limiter.checkAllowed(key) shouldBe true
            limiter.recordFailure(key)
            limiter.checkAllowed(key) shouldBe true
            limiter.recordFailure(key)
            // Third failure reaches maxFailures -- now blocked.
            limiter.checkAllowed(key) shouldBe false
        }

        test("reset() clears an existing failure count, un-blocking the key") {
            val limiter = LoginRateLimiter(maxFailures = 1)
            val key = "email:reset-me@example.org"

            limiter.recordFailure(key)
            limiter.checkAllowed(key) shouldBe false

            limiter.reset(key)
            limiter.checkAllowed(key) shouldBe true
        }

        test("keys are independent -- hammering one key never blocks a different key") {
            val limiter = LoginRateLimiter(maxFailures = 1)
            limiter.recordFailure("email:attacker-target@example.org")
            limiter.checkAllowed("email:attacker-target@example.org") shouldBe false
            limiter.checkAllowed("email:innocent-bystander@example.org") shouldBe true
        }

        test("the sliding window expires -- a key blocked inside the window becomes allowed again after it") {
            val limiter = LoginRateLimiter(maxFailures = 1, window = 10.milliseconds)
            val key = "email:short-window@example.org"

            limiter.recordFailure(key)
            limiter.checkAllowed(key) shouldBe false

            Thread.sleep(50)
            limiter.checkAllowed(key) shouldBe true
        }

        test("recordFailure() after window expiry starts a fresh window rather than accumulating forever") {
            val limiter = LoginRateLimiter(maxFailures = 2, window = 10.milliseconds)
            val key = "email:fresh-window@example.org"

            limiter.recordFailure(key)
            Thread.sleep(50)
            // Old failure has expired -- this is effectively failure #1 of a new window, not #2.
            limiter.recordFailure(key)
            limiter.checkAllowed(key) shouldBe true
        }

        test("IP key and email key for the same login attempt are tracked independently, both trip the limiter") {
            val limiter = LoginRateLimiter(maxFailures = 1)
            limiter.recordFailure("ip:203.0.113.7")
            limiter.checkAllowed("ip:203.0.113.7") shouldBe false
            limiter.checkAllowed("email:victim@example.org") shouldBe true
        }
    })
