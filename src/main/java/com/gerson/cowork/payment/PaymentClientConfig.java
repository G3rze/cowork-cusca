package com.gerson.cowork.payment;

import com.gerson.cowork.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class PaymentClientConfig {

	@Bean
	RestClient paymentRestClient(AppProperties properties) {
		AppProperties.Payment payment = properties.payment();
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(payment.connectTimeout())
				.version(HttpClient.Version.HTTP_1_1)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		// Without a read timeout a slow provider would hold the calling thread (and its resources) indefinitely
		requestFactory.setReadTimeout(payment.readTimeout());
		return RestClient.builder()
				.baseUrl(payment.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}
}
