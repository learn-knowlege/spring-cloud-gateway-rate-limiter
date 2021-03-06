package org.springframework.cloud.gateway.ratelimiter.cloudfoundry;

import java.net.Inet4Address;
import java.net.UnknownHostException;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.ratelimiter.cluster.MemberInfo;

public class HostnameResolvableAvailabilityChecker implements MemberAvailabilityChecker {

	@Override
	public Mono<MemberInfo> check(MemberInfo memberInfo) {
		try {
			Inet4Address.getByName(memberInfo.getHost());
			return Mono.just(memberInfo);
		}
		catch (UnknownHostException e) {
			return Mono.error(e);
		}
	}
}
