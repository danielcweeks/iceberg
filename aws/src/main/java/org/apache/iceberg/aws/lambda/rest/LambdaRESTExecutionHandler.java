/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.aws.lambda.rest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.responses.ErrorResponse;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.utils.IoUtils;

/**
 * A client to fulfill REST catalog request and response using AWS Lambda's Invoke API.
 *
 * <p>This is useful for situations where an HTTP connection cannot be established against a REST
 * endpoint. For example, when the endpoint is in an isolated private subnet, a Lambda can be placed
 * within the subnet as a proxy for communication. See {@link LambdaRESTRequest} and {@link
 * LambdaRESTResponse} for request-response contract
 */
public class LambdaRESTExecutionHandler implements ExecChainHandler, Closeable {

  private LambdaClient lambda;
  private String functionArn;
  private Map<String, String> baseHeaders;

  public LambdaRESTExecutionHandler() {}

  @VisibleForTesting
  LambdaRESTExecutionHandler(
      LambdaClient lambda,
      String functionArn) {
    this.lambda = lambda;
    this.functionArn = functionArn;
  }

  @Override
  public ClassicHttpResponse execute(ClassicHttpRequest httpRequest, ExecChain.Scope scope, ExecChain chain) throws IOException, HttpException {
    String path = httpRequest.getPath();

    URI uri;

    try {
      uri = httpRequest.getUri();
    } catch(URISyntaxException e) {
      throw new RESTException(e, "Invalid URI: %s", httpRequest);
    }

    Map<String, String> headers = Arrays.stream(httpRequest.getHeaders())
            .collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));
    String entity = "";

    if (httpRequest.getEntity() != null) {
      entity = IoUtils.toUtf8String(((StringEntity) httpRequest.getEntity()).getContent());
    }

    LambdaRESTRequest lambdaRequest =
            ImmutableLambdaRESTRequest.builder()
                    .method(httpRequest.getMethod())
                    .uri(uri)
                    .headers(headers)
                    .entity(entity)
                    .build();

    InvokeResponse lambdaResponse;
    try {
      lambdaResponse =
              lambda.invoke(
                      InvokeRequest.builder()
                              .functionName(functionArn)
                              .payload(SdkBytes.fromInputStream(httpRequest.getEntity().getContent()))
                              .build());
    } catch (AwsServiceException e) {
      throw new RESTException(e, "Error occurred while processing request: %s", httpRequest);
    }

    LambdaRESTResponse response =
            LambdaRESTResponseParser.fromJsonStream(lambdaResponse.payload().asInputStream());

    BasicClassicHttpResponse httpResponse = new BasicClassicHttpResponse(response.code());
    httpResponse.setReasonPhrase(response.reason());
    response.headers().forEach(httpResponse::setHeader);

    if(response.entity() != null) {
      httpResponse.setEntity(new StringEntity(response.entity()));
    }


    return httpResponse;
  }

  @Override
  public void close() throws IOException {
    lambda.close();
  }

  public void initialize(Map<String, String> properties) {
    LambdaRESTInvokerProperties lambdaRESTInvokerProperties =
        new LambdaRESTInvokerProperties(properties);
    this.functionArn = lambdaRESTInvokerProperties.functionArn();
    this.lambda = LambdaRESTInvokerAwsClientFactories.from(properties).lambda();
    // TODO: support sigv4 signer
  }

  private Map<String, String> requestHeaders(
      Map<String, String> inputHeaders, String bodyMimeType) {
    return ImmutableMap.<String, String>builder()
        .putAll(baseHeaders)
        .putAll(inputHeaders)
        .put(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType())
        .put(HttpHeaders.CONTENT_TYPE, bodyMimeType)
        .buildKeepingLast();
  }
}
