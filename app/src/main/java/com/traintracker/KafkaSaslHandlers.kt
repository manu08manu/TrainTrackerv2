package com.traintracker

import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler
import javax.security.auth.callback.Callback
import javax.security.auth.callback.PasswordCallback
import javax.security.auth.callback.UnsupportedCallbackException
import javax.security.auth.login.AppConfigurationEntry

/**
 * Android-compatible SASL/PLAIN credential handlers.
 *
 * AppConfigurationEntry exists on Android at runtime but is absent from the
 * SDK compile stubs — a local stub in javax/security/auth/login/ satisfies
 * the compiler; the real class is used at runtime.
 *
 * NameCallback similarly exists at runtime but not in compile stubs; we detect
 * it by class name and call setName() via reflection.
 */
abstract class PlainCallbackHandler : AuthenticateCallbackHandler {

    abstract val username: String
    abstract val password: String

    override fun configure(
        configs: Map<String, *>,
        saslMechanism: String,
        jaasConfigEntries: List<AppConfigurationEntry>
    ) { /* credentials come from Constants — no JAAS config needed */ }

    override fun handle(callbacks: Array<Callback>) {
        for (cb in callbacks) {
            when {
                cb.javaClass.name == "javax.security.auth.callback.NameCallback" -> {
                    cb.javaClass.getMethod("setName", String::class.java).invoke(cb, username)
                }
                cb is PasswordCallback -> cb.password = password.toCharArray()
                else -> throw UnsupportedCallbackException(cb)
            }
        }
    }

    override fun close() {}
}

/** Handler for the Darwin Push Port Kafka feed. */
class DarwinSaslCallbackHandler : PlainCallbackHandler() {
    override val username get() = Constants.DARWIN_USERNAME
    override val password get() = Constants.DARWIN_PASSWORD
}

/** Handler for the TRUST Train Movements Kafka feed. */
class TrustSaslCallbackHandler : PlainCallbackHandler() {
    override val username get() = Constants.TRUST_USERNAME
    override val password get() = Constants.TRUST_PASSWORD
}

/** Handler for the NWR Passenger Train Allocation and Consist Kafka feed. */
class AllocationSaslCallbackHandler : PlainCallbackHandler() {
    override val username get() = Constants.ALLOCATION_USERNAME
    override val password get() = Constants.ALLOCATION_PASSWORD
}

/** Handler for the NWR VSTP (Very Short Term Plan) Kafka feed. */
class VstpSaslCallbackHandler : PlainCallbackHandler() {
    override val username get() = Constants.VSTP_USERNAME
    override val password get() = Constants.VSTP_PASSWORD
}
