package org.springframework.cloud.gateway.ratelimiter;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.atomix.cluster.Node;
import io.atomix.cluster.NodeId;
import io.atomix.cluster.discovery.BootstrapDiscoveryProvider;
import io.atomix.core.Atomix;
import io.atomix.core.map.AsyncAtomicCounterMap;
import io.atomix.protocols.raft.partition.RaftPartitionGroup;
import io.atomix.utils.net.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.core.scheduler.Schedulers;

import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.validation.Validator;

public class AtomixRateLimiter extends AbstractRateLimiter<RateLimiterConfig> {

	private final static int ATOMIX_PORT = 5679;
	private final Logger logger = LoggerFactory.getLogger(AtomixRateLimiter.class);

	private RateLimiterConfig defaultConfig = new RateLimiterConfig();
	private Mono<AsyncAtomicCounterMap<String>> atomicMap;

	AtomixRateLimiter(RateLimiterConfig config, Validator validator, MemberInfo currentNode, Mono<List<MemberInfo>> membersSupplier) {
		this(validator, currentNode, membersSupplier);
		this.defaultConfig = config;
	}

	public AtomixRateLimiter(Validator validator, MemberInfo currentNode, Mono<List<MemberInfo>> membersSupplier) {
		super(RateLimiterConfig.class, "rate-limiter", validator);

		ReplayProcessor<AsyncAtomicCounterMap<String>> processor = ReplayProcessor.create();
		atomicMap = processor.next();

		membersSupplier.map(this::membersToNodes)
		               .map(nodes -> {
			               logger.info("Using nodes {}, this node is {}", nodes, currentNode);

			               final Set<String> allMembers = nodes.stream()
			                                                   .map(Node::id)
			                                                   .map(NodeId::toString)
			                                                   .collect(Collectors.toSet());

			               Atomix atomix = Atomix.builder()
			                                     .withMemberId(currentNode.getHost())
			                                     .withAddress(new Address(currentNode.getHost(), ATOMIX_PORT))
			                                     .withMembershipProvider(BootstrapDiscoveryProvider
					                                     .builder()
					                                     .withNodes(nodes)
					                                     .build())
			                                     .withManagementGroup(RaftPartitionGroup
					                                     .builder("system")
					                                     .withNumPartitions(1)
					                                     .withMembers(allMembers)
					                                     .build())
			                                     .withPartitionGroups(RaftPartitionGroup
					                                     .builder("raft")
					                                     .withNumPartitions(1)
					                                     .withMembers(allMembers)
					                                     .build())
			                                     .build();

			               atomix.start().join();

			               return atomix.<String>getAtomicCounterMap("rate-limit").async();
		               })
		               .subscribeOn(Schedulers.elastic())
		               .doOnNext(processor::onNext)
		               .subscribe();
	}

	@Override
	public Mono<Response> isAllowed(String routeId, String id) {
		final RateLimiterConfig config = getConfig().getOrDefault(routeId, defaultConfig);
		final Response notAllowed = new Response(false, Collections.emptyMap());

		return atomicMap
				.flatMap(atomix -> Mono.fromFuture(atomix.incrementAndGet(id))
				                       .map(noRequests -> {
					                       if (noRequests > config.getLimit()) {
						                       return notAllowed;
					                       }
					                       else {
						                       final int remainingRequests = (int) (config.getLimit() - noRequests);
						                       return new Response(true, Collections.singletonMap("X-Remaining-Limit", String.valueOf(remainingRequests)));
					                       }
				                       }));
	}

	private List<Node> membersToNodes(List<MemberInfo> members) {
		return members.stream()
		              .map(memberInfo -> Node.builder()
		                                     .withId(memberInfo.getHost())
		                                     .withHost(memberInfo.getHost())
		                                     .withPort(ATOMIX_PORT)
		                                     .build())
		              .collect(Collectors.toList());
	}
}