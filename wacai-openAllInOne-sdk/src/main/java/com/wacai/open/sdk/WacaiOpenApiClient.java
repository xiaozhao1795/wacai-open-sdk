package com.wacai.open.sdk;

import static com.wacai.open.sdk.request.WacaiOpenApiHeader.X_WAC_ACCESS_TOKEN;
import static com.wacai.open.sdk.request.WacaiOpenApiHeader.X_WAC_APP_KEY;
import static com.wacai.open.sdk.request.WacaiOpenApiHeader.X_WAC_DECODE;
import static com.wacai.open.sdk.request.WacaiOpenApiHeader.X_WAC_SDK_VERSION;
import static com.wacai.open.sdk.request.WacaiOpenApiHeader.X_WAC_SIGNATURE;
import static com.wacai.open.sdk.request.WacaiOpenApiHeader.X_WAC_TIMESTAMP;
import static com.wacai.open.sdk.request.WacaiOpenApiHeader.X_WAC_TRACE_ID;
import static com.wacai.open.sdk.request.WacaiOpenApiHeader.X_WAC_VERSION;

import com.wacai.open.sdk.errorcode.ErrorCode;
import com.wacai.open.sdk.exception.WacaiOpenApiResponseException;
import com.wacai.open.sdk.json.JsonProcessor;
import com.wacai.open.sdk.json.JsonTool;
import com.wacai.open.sdk.json.TypeReference;
import com.wacai.open.sdk.request.WacaiOpenApiRequest;
import com.wacai.open.sdk.response.WacaiErrorResponse;
import com.wacai.open.sdk.response.WacaiOpenApiResponseCallback;
import com.wacai.open.sdk.util.SignUtil;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;

@Slf4j
public class WacaiOpenApiClient {

	private static final MediaType JSON_MEDIA_TYPE = MediaType
			.parse("application/json; charset=utf-8");

	private static final MediaType OBJ_STREAM = MediaType.parse("application/octet-stream");

	private static final List<String> SIGN_HEADERS = Arrays.asList(X_WAC_VERSION, X_WAC_TIMESTAMP,
			X_WAC_ACCESS_TOKEN, X_WAC_APP_KEY);

	private final String appKey;

	private final String appSecret;

	@Setter
	private OkHttpClient client;

	private final AtomicBoolean initFlag = new AtomicBoolean(false);

	@Setter
	private String gatewayEntryUrl = "https://open.wacai.com/gw/api_entry";

	@Deprecated
	@Setter
	private String gatewayAuthUrl = "https://open.wacai.com/gw/auth";

	@Setter
	private JsonProcessor processor;

	public WacaiOpenApiClient(String appKey, String appSecret) {
		this.appKey = appKey;
		this.appSecret = appSecret;
	}

	public static WacaiOpenApiClient init(String appKey, String appSecret) {
		WacaiOpenApiClient wacaiOpenApiClient = new WacaiOpenApiClient(appKey, appSecret);
		wacaiOpenApiClient.init();
		return wacaiOpenApiClient;
	}

	public void init() {
		if (!initFlag.compareAndSet(false, true)) {
			throw new IllegalStateException("init state");
		}

		if (gatewayEntryUrl == null || gatewayEntryUrl.trim().length() <= 0) {
			throw new IllegalArgumentException("invalid gatewayEntryUrl " + gatewayEntryUrl);
		}

		if (gatewayAuthUrl == null || gatewayAuthUrl.trim().length() <= 0) {
			throw new IllegalArgumentException("invalid gatewayAuthUrl " + gatewayAuthUrl);
		}

		if (client == null) {
			this.client = new OkHttpClient();
		}
		JsonTool.initJsonProcess(processor);
	}

	public <T> T invoke(WacaiOpenApiRequest wacaiOpenApiRequest, TypeReference<T> typeReference) {
		return doInvoke(wacaiOpenApiRequest, typeReference.getType());
	}

	public <T> T invoke(WacaiOpenApiRequest wacaiOpenApiRequest, Class<T> clazz) {
		return doInvoke(wacaiOpenApiRequest, clazz);
	}

	private boolean isNeedDecode(Response response) {
		return response.header(X_WAC_DECODE) == null;
	}

	private String parseTraceId(Response response) {
		return response.header(X_WAC_TRACE_ID);
	}

	private <T> T doInvoke(WacaiOpenApiRequest wacaiOpenApiRequest, Type type) {
		Request request = assemblyRequest(wacaiOpenApiRequest);
		try (Response response = client.newCall(request).execute()) {
			ResponseBody body = response.body();

			if (body == null) {
				log.error("response body is null");
				throw new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR);
			}

			if (response.code() == 200) {
				if (isNeedDecode(response)) {
					String responseBodyString = body.string();
					return deserialization(responseBodyString, type);
				} else {
					return (T) body.bytes();
				}
			} else if (response.code() == 400) {
				String responseBodyString = body.string();
				WacaiErrorResponse wacaiErrorResponse;
				try {
					wacaiErrorResponse = JsonTool
							.deserialization(responseBodyString, WacaiErrorResponse.class);
				} catch (Exception e) {
					log.error("failed to deserialization {}", responseBodyString, e);
					throw new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR);
				}
				throw new WacaiOpenApiResponseException(wacaiErrorResponse);
			}

			String responseBodyString = body.string();
			log.error("sdk error request log, traceId:{}, api:{},httpCode:{},httpBodyMsg:{} ",
					parseTraceId(response),
					wacaiOpenApiRequest.getApiName(), response.code(), responseBodyString);
			throw new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR);
		} catch (IOException e) {
			log.error("failed to execute {}", request, e);
			throw new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR, e);
		} catch (ClassCastException e) {
			throw new WacaiOpenApiResponseException(ErrorCode.ERROR_RET_TYPE, e);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T deserialization(String json, Type type) {
		if (String.class.equals(type)) {
			return (T) json;
		}
		return JsonTool.deserialization(json, type);
	}

	private byte[] assemblyRequestBody(WacaiOpenApiRequest wacaiOpenApiRequest) {
		Map<String, Object> bizParam = wacaiOpenApiRequest.getBizParam();
		return JsonTool.serialization(bizParam);
	}

	public <T> void invoke(final WacaiOpenApiRequest wacaiOpenApiRequest,
			final TypeReference<T> typeReference,
			final WacaiOpenApiResponseCallback<T> callback) {
		doInvoke(wacaiOpenApiRequest, typeReference.getType(), callback);
	}

	public <T> void invoke(final WacaiOpenApiRequest wacaiOpenApiRequest,
			final Class<T> clazz,
			final WacaiOpenApiResponseCallback<T> callback) {
		doInvoke(wacaiOpenApiRequest, clazz, callback);
	}

	private <T> void doInvoke(final WacaiOpenApiRequest wacaiOpenApiRequest,
			final Type type,
			final WacaiOpenApiResponseCallback<T> callback) {
		Request request = assemblyRequest(wacaiOpenApiRequest);

		client.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				callback.onFailure(new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR, e));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				ResponseBody body = response.body();
				if (body == null) {
					callback.onFailure(new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR));
					return;
				}

				if (response.code() == 200) {
					if (isNeedDecode(response)) {
						String responseBodyString = body.string();
						T data = deserialization(responseBodyString, type);
						callback.onSuccess(data);
						return;
					}
					T bytes;
					try {
						bytes = (T) body.bytes();
						callback.onSuccess(bytes);
					} catch (IOException e) {
						log.error("read response body error", e);
						callback.onFailure(new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR));
					} catch (ClassCastException e) {
						callback.onFailure(new WacaiOpenApiResponseException(ErrorCode.ERROR_RET_TYPE));
					}
					return;
				} else if (response.code() == 400) {
					WacaiErrorResponse wacaiErrorResponse;
					String responseBodyString = body.string();
					try {
						wacaiErrorResponse = JsonTool
								.deserialization(responseBodyString, WacaiErrorResponse.class);
					} catch (Exception e) {
						log.error("failed to deserialization {}", responseBodyString, e);
						callback.onFailure(new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR));
						return;
					}
					callback.onFailure(new WacaiOpenApiResponseException(wacaiErrorResponse));
					return;
				}
				// http code 非 200 也不是 400
				log.error("traceId {},api {},request {}, response code is {}", parseTraceId(response),
						wacaiOpenApiRequest.getApiName(), wacaiOpenApiRequest, response.code());
				callback.onFailure(new WacaiOpenApiResponseException(ErrorCode.CLIENT_SYSTEM_ERROR));
			}
		});
	}

	private Request assemblyRequest(WacaiOpenApiRequest wacaiOpenApiRequest) {
		if (!initFlag.get()) {
			throw new IllegalStateException(
					"Not initial client, please call init method before doInvoke");
		}

		byte[] bodyBytes = assemblyRequestBody(wacaiOpenApiRequest);

		byte[] byteBuffer = wacaiOpenApiRequest.getByteBuffer();

		MediaType mediaType = JSON_MEDIA_TYPE;
		if (byteBuffer != null && byteBuffer.length > 0) {
			bodyBytes = byteBuffer;
			mediaType = OBJ_STREAM;
		}

		Map<String, String> headerMap = new HashMap<>();
		headerMap.put(X_WAC_VERSION, String.valueOf(Version.getProtocolVersion()));
		headerMap.put(X_WAC_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
		headerMap.put(X_WAC_SDK_VERSION, Version.getSdkVersion());
		headerMap.put(X_WAC_APP_KEY, appKey);

		String signature = generateSignature(wacaiOpenApiRequest.getApiName(),
				wacaiOpenApiRequest.getApiVersion(),
				headerMap, bodyBytes);
		headerMap.put(X_WAC_SIGNATURE, signature);

		String url = gatewayEntryUrl + "/" + wacaiOpenApiRequest.getApiName() + "/"
				+ wacaiOpenApiRequest.getApiVersion();
		return new Request.Builder().url(url).headers(Headers.of(headerMap))
				.post(RequestBody.create(mediaType, bodyBytes))
				.build();
	}

	private String generateSignature(String apiName, String apiVersion,
			Map<String, String> headerMap, byte[] bodyBytes) {
		String headerString = generateHeadersPlainText(headerMap);

		String bodyMd5 = Base64.encodeBase64String(DigestUtils.md5(bodyBytes));

		String signPlainText = apiName + "|" + apiVersion + "|" + headerString + "|" + bodyMd5;
		return SignUtil.generateSign(signPlainText, appSecret);
	}

	private String generateHeadersPlainText(Map<String, String> headerMap) {
		Map<String, String> headersForSign = new TreeMap<>();
		for (Entry<String, String> entry : headerMap.entrySet()) {
			if (SIGN_HEADERS.contains(entry.getKey())) {
				headersForSign.put(entry.getKey(), entry.getValue());
			}
		}

		StringBuilder headerStringBuilder = new StringBuilder();
		for (Entry<String, String> entry : headersForSign.entrySet()) {
			headerStringBuilder.append('&').append(entry.getKey()).append("=").append(entry.getValue());
		}

		return headerStringBuilder.substring(1);
	}
}
