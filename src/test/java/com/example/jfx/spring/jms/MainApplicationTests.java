package com.example.jfx.spring.jms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MainApplicationTests
{

    @Autowired(required = false)
    JmsConnectionService jmsConnectionService;

    @Test
    void givenApplicationContextIsLoadedThenJmsConnectionServiceShouldNotBeNull()
    {
        assertThat(jmsConnectionService).isNotNull();
    }
}
