package org.letter33.commercesearchlab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "search.elasticsearch.enabled=false")
class CommerceSearchLabApplicationTests {

	@Test
	void contextLoads() {
	}

}
