/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package play.libs.ws.ahc;

import akka.stream.Materializer;
import akka.stream.javadsl.AsPublisher;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.*;
import org.asynchttpclient.oauth.OAuthSignatureCalculator;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.util.HttpUtils;
import org.reactivestreams.Publisher;
import play.api.libs.ws.ahc.Streamed;
import play.core.parsers.FormUrlEncodedParser;
import play.libs.F;
import play.libs.Json;
import play.libs.oauth.OAuth;
import play.libs.ws.*;

import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionStage;

/**
 * provides the User facing API for building WS request.
 */
public class AhcWSRequest implements WSRequest {

    private final String url;
    private String method = "GET";
    private Object body = null;
    private Map<String, Collection<String>> headers = new HashMap<String, Collection<String>>();
    private Map<String, Collection<String>> queryParameters = new HashMap<String, Collection<String>>();

    private String username;
    private String password;
    private WSAuthScheme scheme;
    private WSSignatureCalculator calculator;
    private final AhcWSClient client;

    private final Materializer materializer;

    private int timeout = 0;
    private Boolean followRedirects = null;
    private String virtualHost = null;

    public AhcWSRequest(AhcWSClient client, String url, Materializer materializer) {
        this.client = client;
        URI reference = URI.create(url);

        this.url = url;
        this.materializer = materializer;
        String userInfo = reference.getUserInfo();
        if (userInfo != null) {
            this.setAuth(userInfo);
        }
        if (reference.getQuery() != null) {
            this.setQueryString(reference.getQuery());
        }
    }

    /**
     * Sets a header with the given name, this can be called repeatedly.
     *
     * @param name the header name
     * @param value the header value
     * @return the receiving WSRequest, with the new header set.
     */
    @Override
    public AhcWSRequest setHeader(String name, String value) {
        if (headers.containsKey(name)) {
            Collection<String> values = headers.get(name);
            values.add(value);
        } else {
            List<String> values = new ArrayList<String>();
            values.add(value);
            headers.put(name, values);
        }
        return this;
    }

    /**
     * Sets a query string
     *
     * @param query
     */
    @Override
    public WSRequest setQueryString(String query) {
        String[] params = query.split("&");
        for (String param : params) {
            String[] keyValue = param.split("=");
            if (keyValue.length > 2) {
                throw new RuntimeException(new MalformedURLException("QueryString parameter should not have more than 2 = per part"));
            } else if (keyValue.length >= 2) {
                this.setQueryParameter(keyValue[0], keyValue[1]);
            } else if (keyValue.length == 1 && param.charAt(0) != '=') {
                this.setQueryParameter(keyValue[0], null);
            } else {
                throw new RuntimeException(new MalformedURLException("QueryString part should not start with an = and not be empty"));
            }
        }
        return this;
    }

    @Override
    public WSRequest setQueryParameter(String name, String value) {
        if (queryParameters.containsKey(name)) {
            Collection<String> values = queryParameters.get(name);
            values.add(value);
        } else {
            List<String> values = new ArrayList<String>();
            values.add(value);
            queryParameters.put(name, values);
        }
        return this;
    }

    @Override
    public WSRequest setAuth(String userInfo) {
        this.scheme = WSAuthScheme.BASIC;

        if (userInfo.equals("")) {
            throw new RuntimeException(new MalformedURLException("userInfo should not be empty"));
        }

        int split = userInfo.indexOf(":");

        if (split == 0) { // We only have a password without user
            this.username = "";
            this.password = userInfo.substring(1);
        } else if (split == -1) { // We only have a username without password
            this.username = userInfo;
            this.password = "";
        } else {
            this.username = userInfo.substring(0, split);
            this.password = userInfo.substring(split + 1);
        }

        return this;
    }

    @Override
    public WSRequest setAuth(String username, String password) {
        this.username = username;
        this.password = password;
        this.scheme = WSAuthScheme.BASIC;
        return this;
    }

    @Override
    public WSRequest setAuth(String username, String password, WSAuthScheme scheme) {
        this.username = username;
        this.password = password;
        this.scheme = scheme;
        return this;
    }

    @Override
    public WSRequest sign(WSSignatureCalculator calculator) {
        this.calculator = calculator;
        return this;
    }

    @Override
    public WSRequest setFollowRedirects(Boolean followRedirects) {
        this.followRedirects = followRedirects;
        return this;
    }

    @Override
    public WSRequest setVirtualHost(String virtualHost) {
        this.virtualHost = virtualHost;
        return this;
    }

    @Override
    public WSRequest setRequestTimeout(long timeout) {
        if (timeout < -1 || timeout > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Timeout must be between -1 and " + Integer.MAX_VALUE + " inclusive");
        }
        this.timeout = (int) timeout;
        return this;
    }

    @Override
    public WSRequest setContentType(String contentType) {
        return setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType);
    }

    @Override
    public WSRequest setMethod(String method) {
        this.method = method;
        return this;
    }

    @Override
    public WSRequest setBody(String body) {
        this.body = body;
        return this;
    }

    @Override
    public WSRequest setBody(JsonNode body) {
        this.body = body;
        return this;
    }

    @Override
    public WSRequest setBody(InputStream body) {
        this.body = body;
        return this;
    }

    @Override
    public WSRequest setBody(File body) {
        this.body = body;
        return this;
    }

    @Override
    public WSRequest setBody(Source<ByteString,?> body) {
      this.body = body;
      return this;
    }

    @Override
    public String getUrl() {
        return this.url;
    }

    @Override
    public Map<String, Collection<String>> getHeaders() {
        return new HashMap<String, Collection<String>>(this.headers);
    }

    @Override
    public Map<String, Collection<String>> getQueryParameters() {
        return new HashMap<String, Collection<String>>(this.queryParameters);
    }

    @Override
    public String getUsername() {
        return this.username;
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public WSAuthScheme getScheme() {
        return this.scheme;
    }

    @Override
    public WSSignatureCalculator getCalculator() {
        return this.calculator;
    }

    @Override
    public long getRequestTimeout() {
        return this.timeout;
    }

    @Override
    public Boolean getFollowRedirects() {
        return this.followRedirects;
    }

    // Intentionally package public.
    String getVirtualHost() {
        return this.virtualHost;
    }

    @Override
    public F.Promise<WSResponse> get() {
        return execute("GET");
    }

    //-------------------------------------------------------------------------
    // PATCH
    //-------------------------------------------------------------------------

    @Override
    public F.Promise<WSResponse> patch(String body) {
        setMethod("PATCH");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> patch(JsonNode body) {
        setMethod("PATCH");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> patch(InputStream body) {
        setMethod("PATCH");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> patch(File body) {
        setMethod("PATCH");
        setBody(body);
        return execute();
    }

    //-------------------------------------------------------------------------
    // POST
    //-------------------------------------------------------------------------

    @Override
    public F.Promise<WSResponse> post(String body) {
        setMethod("POST");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> post(JsonNode body) {
        setMethod("POST");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> post(InputStream body) {
        setMethod("POST");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> post(File body) {
        setMethod("POST");
        setBody(body);
        return execute();
    }

    //-------------------------------------------------------------------------
    // PUT
    //-------------------------------------------------------------------------

    @Override
    public F.Promise<WSResponse> put(String body) {
        setMethod("PUT");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> put(JsonNode body) {
        setMethod("PUT");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> put(InputStream body) {
        setMethod("PUT");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> put(File body) {
        setMethod("PUT");
        setBody(body);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> delete() {
        return execute("DELETE");
    }

    @Override
    public F.Promise<WSResponse> head() {
        return execute("HEAD");
    }

    @Override
    public F.Promise<WSResponse> options() {
        return execute("OPTIONS");
    }

    @Override
    public F.Promise<WSResponse> execute(String method) {
        setMethod(method);
        return execute();
    }

    @Override
    public F.Promise<WSResponse> execute() {
        Request request = buildRequest();
        return execute(request);
    }

    @Override
    public CompletionStage<StreamedResponse> stream() {
    	AsyncHttpClient asyncClient = (AsyncHttpClient) client.getUnderlying();
    	Request request = buildRequest();
    	return StreamedResponse.from(Streamed.execute(asyncClient, request));
    }

    Request buildRequest() {
        FluentCaseInsensitiveStringsMap possiblyModifiedHeaders = new FluentCaseInsensitiveStringsMap(this.headers);

        RequestBuilder builder = new RequestBuilder(method);

        builder.setUrl(url);
        builder.setQueryParams(new FluentStringsMap(queryParameters));

        if (body == null) {
            // do nothing
        } else if (body instanceof String) {
            String stringBody = ((String) body);

            // Detect and maybe add charset
            String contentType = possiblyModifiedHeaders.getFirstValue(HttpHeaders.Names.CONTENT_TYPE);
            if (contentType == null) {
                contentType = "text/plain";
            }
            Charset charset = HttpUtils.parseCharset(contentType);
            List<String> contentTypeList = new ArrayList<String>();
            if (charset == null) {
                charset = StandardCharsets.UTF_8;
                contentTypeList.add(contentType + "; charset=" + charset.name().toLowerCase());
            } else {
                contentTypeList.add(contentType);
            }
            // Always replace the content type header to make sure exactly one exists
            possiblyModifiedHeaders.replace(HttpHeaders.Names.CONTENT_TYPE, contentTypeList);

            byte[] bodyBytes;
            bodyBytes = stringBody.getBytes(charset);

            // If using a POST with OAuth signing, the builder looks at
            // getFormParams() rather than getBody() and constructs the signature
            // based on the form params.
            if (contentType.equals(HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED)) {
                Map<String, List<String>> stringListMap = FormUrlEncodedParser.parseAsJava(stringBody, "utf-8");
                for (String key : stringListMap.keySet()) {
                    List<String> values = stringListMap.get(key);
                    for (String value : values) {
                        builder.addFormParam(key, value);
                    }
                }
            } else {
                builder.setBody(stringBody);
            }

            builder.setBodyCharset(charset);
        } else if (body instanceof JsonNode) {
            JsonNode jsonBody = (JsonNode) body;
            List<String> contentType = new ArrayList<String>();
            contentType.add("application/json; charset=utf-8");
            possiblyModifiedHeaders.replace(HttpHeaders.Names.CONTENT_TYPE, contentType);
            String bodyStr = Json.stringify(jsonBody);
            byte[] bodyBytes;
            try {
                bodyBytes = bodyStr.getBytes("utf-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }

            builder.setBody(bodyStr);
            builder.setBodyCharset(StandardCharsets.UTF_8);
        } else if (body instanceof File) {
            File fileBody = (File) body;
            FileBodyGenerator bodyGenerator = new FileBodyGenerator(fileBody);
            builder.setBody(bodyGenerator);
        } else if (body instanceof InputStream) {
            InputStream inputStreamBody = (InputStream) body;
            InputStreamBodyGenerator bodyGenerator = new InputStreamBodyGenerator(inputStreamBody);
            builder.setBody(bodyGenerator);
        } else if (body instanceof Source) {
            Source<ByteString,?> sourceBody = (Source<ByteString,?>) body;
            Publisher<ByteBuffer> publisher = sourceBody.map(ByteString::toByteBuffer)
                .runWith(Sink.asPublisher(AsPublisher.WITHOUT_FANOUT), materializer);
            builder.setBody(publisher);
        } else {
            throw new IllegalStateException("Impossible body: " + body);
        }

        builder.setHeaders(possiblyModifiedHeaders);

        if (this.timeout == -1 || this.timeout > 0) {
            builder.setRequestTimeout(this.timeout);
        }

        if (this.followRedirects != null) {
            builder.setFollowRedirect(this.followRedirects);
        }
        if (this.virtualHost != null) {
            builder.setVirtualHost(this.virtualHost);
        }

        if (this.username != null && this.password != null && this.scheme != null) {
            builder.setRealm(auth(this.username, this.password, this.scheme));
        }

        if (this.calculator != null) {
            if (this.calculator instanceof OAuth.OAuthCalculator) {
                OAuthSignatureCalculator calc = ((OAuth.OAuthCalculator) this.calculator).getCalculator();
                builder.setSignatureCalculator(calc);
            } else {
                throw new IllegalStateException("Use OAuth.OAuthCalculator");
            }
        }

        return builder.build();
    }

    private F.Promise<WSResponse> execute(Request request) {

        final scala.concurrent.Promise<WSResponse> scalaPromise = scala.concurrent.Promise$.MODULE$.<WSResponse>apply();
        try {
            AsyncHttpClient asyncHttpClient = (AsyncHttpClient) client.getUnderlying();
            asyncHttpClient.executeRequest(request, new AsyncCompletionHandler<Response>() {
                @Override
                public Response onCompleted(Response response) {
                    final Response ahcResponse = response;
                    scalaPromise.success(new AhcWSResponse(ahcResponse));
                    return response;
                }

                @Override
                public void onThrowable(Throwable t) {
                    scalaPromise.failure(t);
                }
            });
        } catch (RuntimeException exception) {
            scalaPromise.failure(exception);
        }
        return F.Promise.wrap(scalaPromise.future());
    }

    Realm auth(String username, String password, WSAuthScheme scheme) {
        Realm.AuthScheme authScheme = Realm.AuthScheme.valueOf(scheme.name());
        return (new Realm.RealmBuilder())
                .setScheme(authScheme)
                .setPrincipal(username)
                .setPassword(password)
                .setUsePreemptiveAuth(true)
                .build();
    }
}
