package com.github.coopernetes.jgitproxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.coopernetes.jgitproxy.junk.BaseOptions;
import com.github.coopernetes.jgitproxy.junk.ConcreteOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JacksonMappingTest {
    @Autowired
    private ObjectMapper mapper;

    @Test
    void testPolymorphicDeserialization() throws Exception {
        String json =
                """
            {
              "type": "CONCRETE",
              "value": "test"
            }
            """;
        BaseOptions result = mapper.readValue(json, BaseOptions.class);
        assertThat(result).isInstanceOf(ConcreteOptions.class);
    }
}
