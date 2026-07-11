package com.example.jfx.spring.jms;

import jakarta.annotation.PreDestroy;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageListener;
import jakarta.jms.Session;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Owns the lifecycle of a single JMS connection/session/consumer to an Artemis broker.
 */
@Slf4j
@Component
class JmsConnectionService
{

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;

    synchronized void connect(String brokerUrl, String username, String password) throws JMSException
    {
        disconnect();

        var connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
        connection = StringUtils.hasText(username)
                ? connectionFactory.createConnection(username, password)
                : connectionFactory.createConnection();
        connection.start();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    }

    synchronized void listen(String destinationName, DestinationType destinationType, MessageListener listener)
            throws JMSException
    {
        if (session == null)
        {
            throw new IllegalStateException("Not connected to a broker");
        }

        stopListening();
        Destination destination = destinationType == DestinationType.TOPIC
                ? session.createTopic(destinationName)
                : session.createQueue(destinationName);
        consumer = session.createConsumer(destination);
        consumer.setMessageListener(listener);
    }

    synchronized void stopListening()
    {
        if (consumer != null)
        {
            try
            {
                consumer.close();
            }
            catch (JMSException ex)
            {
                log.warn("Failed to close consumer", ex);
            }
            consumer = null;
        }
    }

    @PreDestroy
    synchronized void disconnect()
    {
        stopListening();

        if (session != null)
        {
            try
            {
                session.close();
            }
            catch (JMSException ex)
            {
                log.warn("Failed to close session", ex);
            }
            session = null;
        }

        if (connection != null)
        {
            try
            {
                connection.close();
            }
            catch (JMSException ex)
            {
                log.warn("Failed to close connection", ex);
            }
            connection = null;
        }
    }

    synchronized boolean isConnected()
    {
        return connection != null;
    }

    synchronized boolean isListening()
    {
        return consumer != null;
    }
}
