package com.example.jfx.spring.jms;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(String title, String indexView, int width, int height)
{

}
