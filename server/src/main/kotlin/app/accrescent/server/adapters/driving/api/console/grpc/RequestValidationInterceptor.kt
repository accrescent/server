// SPDX-FileCopyrightText: © 2026 Logan Magee
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.accrescent.server.adapters.driving.api.console.grpc

import build.buf.protovalidate.Validator
import build.buf.protovalidate.ValidatorFactory
import build.buf.protovalidate.exceptions.ValidationException
import com.google.protobuf.Message
import io.grpc.ForwardingServerCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A gRPC server interceptor which validates request messages against their
 * [protovalidate](https://protovalidate.com) rules.
 *
 * Calls with an invalid request message are closed with [Status.INVALID_ARGUMENT].
 */
class RequestValidationInterceptor : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val closeOnceCall = CloseOnceServerCall(call)
        val listener = next.startCall(closeOnceCall, headers)

        return RequestValidationServerCallListener(closeOnceCall, listener)
    }

    /**
     * A server call which ignores all calls to [close] after the first.
     *
     * This wrapper is needed because our gRPC server implementation may still attempt to close the
     * call after we've closed it.
     */
    private class CloseOnceServerCall<ReqT, RespT>(
        delegate: ServerCall<ReqT, RespT>,
    ) : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(delegate) {
        private val isClosed = AtomicBoolean(false)

        override fun close(status: Status, trailers: Metadata) {
            if (isClosed.compareAndSet(false, true)) {
                super.close(status, trailers)
            }
        }
    }

    private class RequestValidationServerCallListener<ReqT, RespT>(
        private val call: ServerCall<ReqT, RespT>,
        delegate: ServerCall.Listener<ReqT>,
    ) : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
        override fun onMessage(message: ReqT) {
            require(message is Message) {
                "expected message of type ${Message::class.qualifiedName}, but got " +
                        message?.let { it::class.qualifiedName }
            }

            val result = try {
                validator.validate(message)
            } catch (e: ValidationException) {
                logger.error("Failed to validate request message", e)
                call.close(Status.INTERNAL, Metadata())
                return
            }

            if (result.isSuccess) {
                super.onMessage(message)
            } else {
                call.close(Status.INVALID_ARGUMENT.withDescription(result.toString()), Metadata())
            }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RequestValidationInterceptor::class.java)
        private val validator: Validator = ValidatorFactory.newBuilder().build()
    }
}
