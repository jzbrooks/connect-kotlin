// Copyright 2022-2023 The Connect Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.connectrpc.protocols

import com.connectrpc.Code
import com.connectrpc.Codec
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException
import com.connectrpc.Headers
import com.connectrpc.Idempotency
import com.connectrpc.Interceptor
import com.connectrpc.Method.GET_METHOD
import com.connectrpc.MethodSpec
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.RequestCompression
import com.connectrpc.StreamFunction
import com.connectrpc.StreamResult
import com.connectrpc.Trailers
import com.connectrpc.UnaryFunction
import com.connectrpc.compression.CompressionPool
import com.connectrpc.http.HTTPRequest
import com.connectrpc.http.HTTPResponse
import com.squareup.moshi.Moshi
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.net.URL

/**
 * The Connect protocol.
 * https://connectrpc.com/docs
 */
internal class ConnectInterceptor(
    private val clientConfig: ProtocolClientConfig,
) : Interceptor {
    private val moshi = Moshi.Builder().build()
    private val serializationStrategy = clientConfig.serializationStrategy
    private var responseCompressionPool: CompressionPool? = null

    override fun unaryFunction(): UnaryFunction {
        return UnaryFunction(
            requestFunction = { request ->
                val requestHeaders =
                    mutableMapOf(CONNECT_PROTOCOL_VERSION_KEY to listOf(CONNECT_PROTOCOL_VERSION_VALUE))
                requestHeaders.putAll(request.headers)
                if (clientConfig.compressionPools().isNotEmpty()) {
                    requestHeaders.put(
                        ACCEPT_ENCODING,
                        clientConfig.compressionPools()
                            .map { compressionPool -> compressionPool.name() },
                    )
                }
                if (requestHeaders.keys.none { it.equals(USER_AGENT, ignoreCase = true) }) {
                    requestHeaders[USER_AGENT] = listOf("connect-kotlin/${ConnectConstants.VERSION}")
                }
                val requestCompression = clientConfig.requestCompression
                val requestMessage = Buffer()
                if (request.message != null) {
                    requestMessage.write(request.message)
                }
                val finalRequestBody = if (requestCompression?.shouldCompress(requestMessage) == true) {
                    requestHeaders.put(CONTENT_ENCODING, listOf(requestCompression.compressionPool.name()))
                    requestCompression.compressionPool.compress(requestMessage)
                } else {
                    requestMessage
                }
                if (shouldUseGETRequest(request, finalRequestBody)) {
                    constructGETRequest(request, finalRequestBody, requestCompression)
                } else {
                    request.clone(
                        url = request.url,
                        contentType = request.contentType,
                        headers = requestHeaders,
                        message = finalRequestBody.readByteArray(),
                        methodSpec = request.methodSpec,
                    )
                }
            },
            responseFunction = { response ->
                val trailers = mutableMapOf<String, List<String>>()
                trailers.putAll(response.headers.toTrailers())
                trailers.putAll(response.trailers)
                val responseHeaders =
                    response.headers.filter { entry -> !entry.key.startsWith("trailer-") }
                val compressionPool = clientConfig.compressionPool(responseHeaders[CONTENT_ENCODING]?.first())
                val (code, exception) = if (response.code != Code.OK) {
                    val error = parseConnectUnaryException(code = response.code, response.headers, response.message.buffer)
                    error.code to error
                } else {
                    response.code to null
                }
                val message = compressionPool?.decompress(response.message.buffer) ?: response.message.buffer
                HTTPResponse(
                    code = code,
                    message = message,
                    headers = responseHeaders,
                    trailers = trailers,
                    cause = response.cause ?: exception,
                    tracingInfo = response.tracingInfo,
                )
            },
        )
    }

    override fun streamFunction(): StreamFunction {
        val requestCompression = clientConfig.requestCompression
        return StreamFunction(
            requestFunction = { request ->
                val requestHeaders =
                    mutableMapOf(CONNECT_PROTOCOL_VERSION_KEY to listOf(CONNECT_PROTOCOL_VERSION_VALUE))
                requestHeaders.putAll(request.headers)
                if (requestCompression != null) {
                    requestHeaders.put(
                        CONNECT_STREAMING_CONTENT_ENCODING,
                        listOf(requestCompression.compressionPool.name()),
                    )
                }
                if (requestHeaders.keys.none { it.equals(USER_AGENT, ignoreCase = true) }) {
                    requestHeaders[USER_AGENT] = listOf("connect-kotlin/${ConnectConstants.VERSION}")
                }
                requestHeaders.put(
                    CONNECT_STREAMING_ACCEPT_ENCODING,
                    clientConfig.compressionPools().map { entry -> entry.name() },
                )
                request.clone(
                    url = request.url,
                    contentType = request.contentType,
                    headers = requestHeaders,
                    message = request.message,
                    methodSpec = request.methodSpec,
                )
            },
            requestBodyFunction = { buffer ->
                val compressionPool = requestCompression?.compressionPool
                Envelope.pack(buffer, compressionPool, requestCompression?.minBytes)
            },
            streamResultFunction = { res ->
                val streamResult: StreamResult<Buffer> = res.fold(
                    onHeaders = { result ->
                        val responseHeaders =
                            result.headers.filter { entry -> !entry.key.startsWith("trailer-") }
                        responseCompressionPool =
                            clientConfig.compressionPool(responseHeaders[CONNECT_STREAMING_CONTENT_ENCODING]?.first())
                        StreamResult.Headers(responseHeaders)
                    },
                    onMessage = { result ->
                        val (headerByte, unpackedMessage) = Envelope.unpackWithHeaderByte(
                            result.message,
                            responseCompressionPool,
                        )
                        val isEndStream = headerByte.shr(1).and(1) == 1
                        if (isEndStream) {
                            parseConnectEndStream(unpackedMessage)
                        } else {
                            StreamResult.Message(unpackedMessage)
                        }
                    },
                    onCompletion = { result -> result },
                )
                streamResult
            },
        )
    }

    private fun shouldUseGETRequest(request: HTTPRequest, finalRequestBody: Buffer): Boolean {
        return request.methodSpec.idempotency == Idempotency.NO_SIDE_EFFECTS &&
            clientConfig.getConfiguration.useGET(finalRequestBody)
    }

    private fun constructGETRequest(
        request: HTTPRequest,
        finalRequestBody: Buffer,
        requestCompression: RequestCompression?,
    ): HTTPRequest {
        val serializationStrategy = clientConfig.serializationStrategy
        val requestCodec = serializationStrategy.codec(request.methodSpec.requestClass)
        val url = getUrlFromMethodSpec(
            request,
            requestCodec,
            finalRequestBody,
            requestCompression,
        )
        return request.clone(
            url = url,
            contentType = "application/${requestCodec.encodingName()}",
            headers = request.headers,
            methodSpec = MethodSpec(
                path = request.methodSpec.path,
                requestClass = request.methodSpec.requestClass,
                responseClass = request.methodSpec.responseClass,
                idempotency = request.methodSpec.idempotency,
                method = GET_METHOD,
            ),
        )
    }

    private fun parseConnectEndStream(source: Buffer): StreamResult.Complete<Buffer> {
        val adapter = moshi.adapter(EndStreamResponseJSON::class.java)
        return source.use { bufferedSource ->
            val errorJSON = bufferedSource.readUtf8()
            val endStreamResponseJSON = try {
                adapter.fromJson(errorJSON) ?: return StreamResult.Complete(Code.OK)
            } catch (e: Throwable) {
                return StreamResult.Complete(Code.UNKNOWN, e)
            }
            val metadata = endStreamResponseJSON.metadata?.mapKeys { entry -> entry.key.lowercase() }
            if (endStreamResponseJSON.error?.code == null) {
                return StreamResult.Complete(Code.OK, trailers = metadata ?: emptyMap())
            }
            val code = Code.fromName(endStreamResponseJSON.error.code)
            StreamResult.Complete(
                code = code,
                cause = ConnectException(
                    code = code,
                    errorDetailParser = serializationStrategy.errorDetailParser(),
                    message = endStreamResponseJSON.error.message,
                    details = parseErrorDetails(endStreamResponseJSON.error),
                    metadata = metadata ?: emptyMap(),
                ),
            )
        }
    }

    private fun parseConnectUnaryException(code: Code, headers: Headers, source: Buffer?): ConnectException {
        if (source == null) {
            return ConnectException(code, serializationStrategy.errorDetailParser(), "empty error message from source")
        }
        return source.use { bufferedSource ->
            val adapter = moshi.adapter(ErrorPayloadJSON::class.java)
            val errorJSON = bufferedSource.readUtf8()
            val errorPayloadJSON = try {
                adapter.fromJson(errorJSON) ?: return ConnectException(
                    code,
                    serializationStrategy.errorDetailParser(),
                    errorJSON,
                )
            } catch (e: Throwable) {
                return ConnectException(code, serializationStrategy.errorDetailParser(), errorJSON)
            }
            val errorDetails = parseErrorDetails(errorPayloadJSON)
            ConnectException(
                code = Code.fromName(errorPayloadJSON.code),
                errorDetailParser = serializationStrategy.errorDetailParser(),
                message = errorPayloadJSON.message,
                details = errorDetails,
                metadata = headers,
            )
        }
    }

    private fun parseErrorDetails(
        jsonClass: ErrorPayloadJSON?,
    ): List<ConnectErrorDetail> {
        val errorDetails = mutableListOf<ConnectErrorDetail>()
        for (detail in jsonClass?.details.orEmpty()) {
            if (detail.type == null) {
                continue
            }
            val payload = detail.value
            if (payload == null) {
                errorDetails.add(ConnectErrorDetail(detail.type, ByteString.EMPTY))
                continue
            }
            errorDetails.add(
                ConnectErrorDetail(
                    detail.type,
                    payload.encodeUtf8(),
                ),
            )
        }
        return errorDetails
    }
}

private fun Headers.toTrailers(): Trailers {
    val trailers = mutableMapOf<String, MutableList<String>>()
    for (pair in this.filter { entry -> entry.key.startsWith("trailer-") }) {
        val key = pair.key.substringAfter("trailer-")
        if (trailers.containsKey(key)) {
            trailers[key]?.add(pair.value.first())
        } else {
            trailers[key] = mutableListOf(pair.value.first())
        }
    }
    return trailers
}

private fun getUrlFromMethodSpec(
    httpRequest: HTTPRequest,
    codec: Codec<*>,
    payload: Buffer,
    requestCompression: RequestCompression?,
): URL {
    val baseURL = httpRequest.url
    val methodSpec = httpRequest.methodSpec
    val params = mutableListOf<String>()
    if (requestCompression?.shouldCompress(payload) == true) {
        params.add("${GETConstants.COMPRESSION_QUERY_PARAM_KEY}=${requestCompression.compressionPool.name()}")
    }
    params.add("${GETConstants.MESSAGE_QUERY_PARAM_KEY}=${payload.readByteString().base64Url()}")
    params.add("${GETConstants.BASE64_QUERY_PARAM_KEY}=1")
    params.add("${GETConstants.ENCODING_QUERY_PARAM_KEY}=${codec.encodingName()}")
    params.add("${GETConstants.CONNECT_VERSION_QUERY_PARAM_KEY}=${GETConstants.CONNECT_VERSION_QUERY_PARAM_VALUE}")
    params.sort()
    val queryParams = params.joinToString("&")
    val baseURI = baseURL.toURI()
        .resolve("/${methodSpec.path}?$queryParams")
    return baseURI.toURL()
}
