package org.jboss.resteasy.star.messaging.queue;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.jboss.resteasy.spi.Link;
import org.jboss.resteasy.star.messaging.util.Constants;
import org.jboss.resteasy.star.messaging.util.HttpMessageHelper;
import org.jboss.resteasy.star.messaging.util.LinkStrategy;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.net.URI;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class QueueConsumer
{
   protected ClientSessionFactory factory;
   protected ClientSession session;
   protected ClientConsumer consumer;
   protected String destination;
   protected boolean closed;
   protected String id;
   protected long lastPing = System.currentTimeMillis();
   protected DestinationServiceManager serviceManager;

   public DestinationServiceManager getServiceManager()
   {
      return serviceManager;
   }

   public void setServiceManager(DestinationServiceManager serviceManager)
   {
      this.serviceManager = serviceManager;
   }

   public long getLastPingTime()
   {
      return lastPing;
   }

   protected void ping()
   {
      lastPing = System.currentTimeMillis();
   }

   public QueueConsumer(ClientSessionFactory factory, String destination, String id, DestinationServiceManager serviceManager) throws HornetQException
   {
      this.factory = factory;
      this.destination = destination;
      this.id = id;
      this.serviceManager = serviceManager;

      createSession(factory, destination);
   }

   public String getId()
   {
      return id;
   }

   public boolean isClosed()
   {
      return closed;
   }

   public synchronized void shutdown()
   {
      if (closed) return;
      closed = true;
      try
      {
         consumer.close();
      }
      catch (Exception e)
      {
      }

      try
      {
         session.close();
      }
      catch (Exception e)
      {
      }
      session = null;
      consumer = null;
   }


   @Path("consume-next")
   @POST
   public synchronized Response poll(@HeaderParam(Constants.WAIT_HEADER) @DefaultValue("0") long wait,
                                     @Context UriInfo info)
   {
      if (closed)
      {
         UriBuilder builder = info.getBaseUriBuilder();
         builder.path(info.getMatchedURIs().get(1))
                 .path("consume-next");
         String uri = builder.build().toString();

         // redirect to another consume-next

         return Response.status(307).location(URI.create(uri)).build();
      }
      return runPoll(wait, info, info.getMatchedURIs().get(1));
   }

   public synchronized Response runPoll(long wait, UriInfo info, String basePath)
   {
      ping();
      try
      {
         ClientMessage message = receive(wait);
         if (message == null)
         {
            //System.out.println("Timed out waiting for message receive.");
            Response.ResponseBuilder builder = Response.status(503).entity("Timed out waiting for message receive.").type("text/plain");
            setPollTimeoutLinks(info, basePath, builder);
            return builder.build();
         }
         return getMessageResponse(message, info, basePath).build();
      }
      catch (Exception e)
      {
         throw new RuntimeException(e);
      }
   }

   protected void createSession(ClientSessionFactory factory, String destination)
           throws HornetQException
   {
      session = factory.createSession(true, true);
      consumer = session.createConsumer(destination);
      session.start();
   }

   protected ClientMessage receiveFromConsumer(long timeoutSecs) throws Exception
   {
      if (timeoutSecs <= 0)
      {
         return consumer.receiveImmediate();
      }
      else
      {
         return consumer.receive(timeoutSecs * 1000);
      }

   }

   protected ClientMessage receive(long timeoutSecs) throws Exception
   {
      return receiveFromConsumer(timeoutSecs);
   }

   protected void setPollTimeoutLinks(UriInfo info, String basePath, Response.ResponseBuilder builder)
   {
      setSessionLink(builder, info, basePath);
      setConsumeNextLink(serviceManager.getLinkStrategy(), builder, info, basePath);
   }

   protected Response.ResponseBuilder getMessageResponse(ClientMessage msg, UriInfo info, String basePath)
   {
      Response.ResponseBuilder responseBuilder = Response.ok();
      setMessageResponseLinks(info, basePath, responseBuilder);
      HttpMessageHelper.buildMessage(msg, responseBuilder);
      return responseBuilder;
   }

   protected void setMessageResponseLinks(UriInfo info, String basePath, Response.ResponseBuilder responseBuilder)
   {
      setConsumeNextLink(serviceManager.getLinkStrategy(), responseBuilder, info, basePath);
      setSessionLink(responseBuilder, info, basePath);
   }

   public static void setConsumeNextLink(LinkStrategy linkStrategy, Response.ResponseBuilder response, UriInfo info, String basePath)
   {
      UriBuilder builder = info.getBaseUriBuilder();
      builder.path(basePath)
              .path("consume-next");
      String uri = builder.build().toString();
      linkStrategy.setLinkHeader(response, "consume-next", "consume-next", uri, MediaType.APPLICATION_FORM_URLENCODED);
   }

   protected void setSessionLink(Response.ResponseBuilder response, UriInfo info, String basePath)
   {
      UriBuilder builder = info.getBaseUriBuilder();
      builder.path(basePath);
      String uri = builder.build().toString();
      Link link = new Link("session", "session", uri, MediaType.APPLICATION_XML, null);
      serviceManager.getLinkStrategy().setLinkHeader(response, "session", "session", uri, MediaType.APPLICATION_XML);
   }
}