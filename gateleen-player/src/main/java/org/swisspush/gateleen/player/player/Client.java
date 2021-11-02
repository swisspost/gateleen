package org.swisspush.gateleen.player.player;

import org.json.JSONObject;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTTP Client abstraction, suitable for mocking.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Client {

    private RestTemplate restTemplate = new RestTemplate();

    private static class JsonConverter extends AbstractHttpMessageConverter<JSONObject> {

        public JsonConverter() {
            setSupportedMediaTypes(Collections.singletonList(MediaType.ALL));
        }

        @Override
        protected boolean supports(Class<?> aClass) {
            return aClass.isAssignableFrom(JSONObject.class);
        }

        @Override
        protected JSONObject readInternal(Class<? extends JSONObject> clazz, HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
            return new JSONObject(inputMessage.getBody());
        }

        @Override
        protected void writeInternal(JSONObject jsonObject, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
            outputMessage.getBody().write(jsonObject.toString().getBytes("UTF-8"));
        }
    }

    public Client() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new JsonConverter());
        restTemplate.setMessageConverters(converters);
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse clientHttpResponse) throws IOException {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse clientHttpResponse) throws IOException {
            }
        });
    }

    public ResponseEntity<JSONObject> exchange(RequestEntity<JSONObject> request) {
        LoggerFactory.getLogger(this.getClass()).debug(""+request);
        return restTemplate.exchange(request, JSONObject.class);
    }
}
