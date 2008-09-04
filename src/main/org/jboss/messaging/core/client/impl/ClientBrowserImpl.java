/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005-2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */ 
package org.jboss.messaging.core.client.impl;

import org.jboss.messaging.core.client.ClientBrowser;
import org.jboss.messaging.core.client.ClientMessage;
import org.jboss.messaging.core.exception.MessagingException;
import org.jboss.messaging.core.remoting.Channel;
import org.jboss.messaging.core.remoting.impl.wireformat.BrowseMessage;
import org.jboss.messaging.core.remoting.impl.wireformat.ReceiveMessage;
import org.jboss.messaging.core.remoting.impl.wireformat.SessionBrowserCloseMessage;
import org.jboss.messaging.core.remoting.impl.wireformat.SessionBrowserHasNextMessageMessage;
import org.jboss.messaging.core.remoting.impl.wireformat.SessionBrowserHasNextMessageResponseMessage;
import org.jboss.messaging.core.remoting.impl.wireformat.SessionBrowserNextMessageMessage;
import org.jboss.messaging.core.remoting.impl.wireformat.SessionBrowserResetMessage;

/**
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:ovidiu@feodorov.com">Ovidiu Feodorov</a>
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 * @author <a href="mailto:ataylor@redhat.com">Andy Taylor</a>
 *
 * @version <tt>$Revision: 3602 $</tt>
 *
 * $Id: ClientBrowserImpl.java 3602 2008-01-21 17:48:32Z timfox $
 */
public class ClientBrowserImpl implements ClientBrowser
{
   // Constants ------------------------------------------------------------------------------------

   // Attributes -----------------------------------------------------------------------------------

   private final int id;
   
	private final ClientSessionInternal session;
	
	private final Channel channel;
	
	private volatile boolean closed;
	
   // Static ---------------------------------------------------------------------------------------

   // Constructors ---------------------------------------------------------------------------------

   public ClientBrowserImpl(final ClientSessionInternal session,                            
                            final int id,
                            final Channel channel)
   {
      this.id = id;
      
      this.session = session;
      
      this.channel = channel;
   }

   // ClientBrowser implementation -----------------------------------------------------------------
   
   public int getID()
   {
      return id;
   }
   
   public synchronized void close() throws MessagingException
   {
      if (closed)
      {
         return;
      }
      
      try
      {
         channel.sendBlocking(new SessionBrowserCloseMessage(id));
      }
      finally
      {
         session.removeBrowser(this);
         
         closed = true;
      }
   }

   public synchronized void cleanUp()
   {
      session.removeBrowser(this);

      closed = true;
   }

   public boolean isClosed()
   {
      return closed;
   }

   public void reset() throws MessagingException
   {
      checkClosed();
      
      channel.sendBlocking(new SessionBrowserResetMessage(id));
   }

   public boolean hasNextMessage() throws MessagingException
   {
      checkClosed();
      
      SessionBrowserHasNextMessageResponseMessage response =
         (SessionBrowserHasNextMessageResponseMessage)channel.sendBlocking(
                  new SessionBrowserHasNextMessageMessage(id));
      
      return response.hasNext();
   }

   public ClientMessage nextMessage() throws MessagingException
   {
      checkClosed();
      
      BrowseMessage response =
         (BrowseMessage)channel.sendBlocking(new SessionBrowserNextMessageMessage(id));
      
      return response.getClientMessage();
   }

   // Public ---------------------------------------------------------------------------------------

   // Protected ------------------------------------------------------------------------------------

   // Package Private ------------------------------------------------------------------------------

   // Private --------------------------------------------------------------------------------------
   
   private void checkClosed() throws MessagingException
   {
      if (closed)
      {
         throw new MessagingException(MessagingException.OBJECT_CLOSED, "Browser is closed");
      }
   }

   // Inner Classes --------------------------------------------------------------------------------

}
