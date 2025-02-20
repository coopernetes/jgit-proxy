package com.rbc.jgitproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbc.jgitproxy.junk.BaseOptions;
import com.rbc.jgitproxy.junk.ConcreteOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

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
