/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
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
package org.jboss.messaging.core.plugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import javax.transaction.TransactionManager;
import javax.transaction.xa.Xid;

import org.jboss.jms.destination.JBossDestination;
import org.jboss.jms.destination.JBossQueue;
import org.jboss.jms.destination.JBossTopic;
import org.jboss.jms.message.JBossMessage;
import org.jboss.logging.Logger;
import org.jboss.messaging.core.Message;
import org.jboss.messaging.core.MessageReference;
import org.jboss.messaging.core.message.CoreMessage;
import org.jboss.messaging.core.message.MessageFactory;
import org.jboss.messaging.core.message.MessageSupport;
import org.jboss.messaging.core.message.RoutableSupport;
import org.jboss.messaging.core.plugin.contract.PersistenceManager;
import org.jboss.messaging.core.tx.Transaction;
import org.jboss.messaging.core.tx.TxCallback;
import org.jboss.messaging.core.tx.XidImpl;
import org.jboss.messaging.util.JDBCUtil;
import org.jboss.messaging.util.Util;
import org.jboss.serial.io.JBossObjectInputStream;
import org.jboss.serial.io.JBossObjectOutputStream;

/**
 *  
 * JDBC implementation of PersistenceManager 
 *  
 * @author <a href="mailto:ovidiu@jboss.org">Ovidiu Feodorov</a>
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:adrian@jboss.org">Adrian Brock</a>
 *
 * @version <tt>1.1</tt>
 *
 * JDBCPersistenceManager.java,v 1.1 2006/02/22 17:33:41 timfox Exp
 */
public class JDBCPersistenceManager extends JDBCServiceSupport implements PersistenceManager
{
   // Constants -----------------------------------------------------
   
   private static final Logger log = Logger.getLogger(JDBCPersistenceManager.class); 
   
   // Static --------------------------------------------------------
   
   // Attributes ----------------------------------------------------
   
   private boolean trace = log.isTraceEnabled();
      
   protected boolean usingBatchUpdates = false;
   
   protected boolean usingBinaryStream = true;
   
   protected int maxParams = 100;
   
   protected int minOrdering;
   
   // Constructors --------------------------------------------------
   
   public JDBCPersistenceManager()
   {
   }
   
   /*
    * This constructor should only be used for testing
    */
   public JDBCPersistenceManager(DataSource ds, TransactionManager tm)
   {
      super(ds, tm);
   }
      
   // ServiceMBeanSupport overrides ---------------------------------
   
   protected void startService() throws Exception
   {
      super.startService();

      Connection conn = null;
      
      try
      {
         conn = ds.getConnection();      
         //JBossMessaging requires transaction isolation of READ_COMMITTED
         //Any looser isolation level and we cannot maintain consistency for paging (HSQL)
         if (conn.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
         {
            int level = conn.getTransactionIsolation();

            String warn =
               "\n\n" +
               "JBoss Messaging Warning: DataSource connection transaction isolation should be READ_COMMITTED, but it is currently " + Util.transactionIsolationToString(level) + ".\n" +
               "                         Using an isolation level less strict than READ_COMMITTED may lead to data consistency problems.\n" +
               "                         Using an isolation level more strict than READ_COMMITTED may lead to deadlock.\n";
            log.warn(warn);
         }
      }
      finally
      {
         conn.close();
      }
            
      removeUnreliableMessageData();
        
      log.debug(this + " started");
   }
   
   // PersistenceManager implementation -------------------------

   
   // Related to counters
   // ==================
   
   public long reserveIDBlock(String counterName, int size) throws Exception
   {
      //TODO This will need locking (e.g. SELECT ... FOR UPDATE...) in the clustered case
       
      if (trace)
      {
         log.trace("Getting id block for counter: " + counterName + " ,size: " + size);
      }
      
      if (size <= 0)
      {
         throw new IllegalArgumentException("block size must be > 0");
      }
      
      Connection conn = null;
      PreparedStatement ps = null;
      ResultSet rs = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      try
      {
         conn = ds.getConnection();
         
         ps = conn.prepareStatement(getSQLStatement("SELECT_COUNTER"));
         
         ps.setString(1, counterName);
         
         rs = ps.executeQuery();
         
         if (!rs.next())
         {
            rs.close();
            rs = null;
            
            ps.close();
            
            ps = conn.prepareStatement(getSQLStatement("INSERT_COUNTER"));
            
            ps.setString(1, counterName);
            
            ps.setLong(2, size);
            
            int rows = ps.executeUpdate();
            
            if (trace)
            {
               log.trace(JDBCUtil.statementToString(getSQLStatement("INSERT_COUNTER"), counterName)
                     + " inserted " + rows + " rows");
            }  
            
            ps.close();            
            ps = null;
            
            return 0;
         }
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("SELECT_COUNTER"), counterName));
         }
         
         long nextId = rs.getLong(1);
         
         rs.close();
         rs = null;
         
         ps.close();
         
         ps = conn.prepareStatement(getSQLStatement("UPDATE_COUNTER"));
         
         ps.setLong(1, nextId + size);
         
         ps.setString(2, counterName);
         
         int rows = ps.executeUpdate();
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("UPDATE_COUNTER"), new Long(nextId + size),
                  counterName)
                  + " updated " + rows + " rows");
         }        
         
         return nextId;
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (ps != null)
         {
            try
            {
               ps.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         wrap.end();
      }     
   }
         
   // Related to paging functionality
   // ===============================
      
   public void updateReliableReferencesNotPagedInRange(long channelID, long orderStart, long number) throws Exception
   {
      if (trace)
      {
         log.trace("Updating reliable references for channel " + channelID + " between " + orderStart + " number " + number);
      }
      Connection conn = null;
      PreparedStatement ps = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      final int MAX_TRIES = 25;      
      
      try
      {
         conn = ds.getConnection();
         
         ps = conn.prepareStatement(getSQLStatement("UPDATE_RELIABLE_REFS_NOT_PAGED"));
         
         ps.setLong(1, orderStart);
         
         ps.setLong(2, orderStart + number - 1);
         
         ps.setLong(3, channelID);
         
         int tries = 0;
         
         while (true)
         {
            try
            {
               int rows = ps.executeUpdate();
               
               if (trace)
               {
                  log.trace(JDBCUtil.statementToString(getSQLStatement("UPDATE_RELIABLE_REFS_NOT_PAGED"), new Long(channelID),
                        new Long(orderStart), new Long(orderStart + number - 1))
                        + " updated " + rows + " rows");
               }
               if (tries > 0)
               {
                  log.warn("Update worked after retry");
               }
               
               //Sanity check
               if (rows != number)
               {
                  throw new IllegalStateException("Did not update correct number of rows");
               }
               
               break;
            }
            catch (SQLException e)
            {
               log.warn("SQLException caught - assuming deadlock detected, try:" + (tries + 1), e);
               tries++;
               if (tries == MAX_TRIES)
               {
                  log.error("Retried " + tries + " times, now giving up");
                  throw new IllegalStateException("Failed to update references");
               }
               log.warn("Trying again after a pause");
               //Now we wait for a random amount of time to minimise risk of deadlock
               Thread.sleep((long)(Math.random() * 500));
            }  
         }
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (ps != null)
         {
            try
            {
               ps.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         wrap.end();
      }
   }         
        
   /*
    * Retrieve a List of messages corresponding to the specified List of message ids.
    * The implementation here for HSQLDB does this by using a PreparedStatment with an IN clause
    * with a maximum of 100 elements.
    * If there are more than maxParams message to retrieve this is repeated a number of times.
    * For "Enterprise" databases (Oracle, DB2, Sybase etc) a more sophisticated technique should be used
    * e.g. Oracle ARRAY types in Oracle which can be submitted as a param to an Oracle prepared statement
    * Although this would all be DB specific.
    */
   public List getMessages(List messageIds) throws Exception
   {
      if (trace)
      {
         log.trace("Getting batch of messages for " + messageIds);
      }
      
      Connection conn = null;
      PreparedStatement ps = null;
      ResultSet rs = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      try
      {
         conn = ds.getConnection();
         
         Iterator iter = messageIds.iterator();
         
         int size = messageIds.size();
         
         int count = 0;
         
         List msgs = new ArrayList();
         
         while (iter.hasNext())
         {
            if (ps == null)
            {
               //PreparedStatements are cached in the JCA layer so we will never actually have more than
               //100 distinct ones            
               int numParams;
               if (count < (size / maxParams) * maxParams)
               {
                  numParams = maxParams;
               }
               else
               {
                  numParams = size % maxParams;
               }
               StringBuffer buff = new StringBuffer(getSQLStatement("LOAD_MESSAGES"));
               buff.append(" WHERE ").append(getSQLStatement("MESSAGEID_COLUMN")).append(" IN (");
               for (int i = 0; i < numParams; i++)
               {
                  buff.append("?");
                  if (i < numParams - 1)
                  {
                     buff.append(",");
                  }
               }
               buff.append(")");
               ps = conn.prepareStatement(buff.toString());
               
               if (trace)
               {
                  log.trace(buff.toString());
               }
            }
            
            long msgId = ((Long)iter.next()).longValue();
            
            ps.setLong((count % maxParams) + 1, msgId);
            
            count++;
            
            if (!iter.hasNext() || count % maxParams == 0)
            {
               rs = ps.executeQuery();
               
               while (rs.next())
               {
                  long messageId = rs.getLong(1);
                  boolean reliable = rs.getString(2).equals("Y");
                  long expiration = rs.getLong(3);
                  long timestamp = rs.getLong(4);
                  byte priority = rs.getByte(5);
                  byte[] bytes = getBytes(rs, 6);
                  HashMap coreHeaders = bytesToMap(bytes);
                  byte[] payload = getBytes(rs, 7);
                  int persistentChannelCount = rs.getInt(8);
                  
                  //FIXME - We are mixing concerns here
                  //The basic JDBCPersistencManager should *only* know about core messages - not 
                  //JBossMessages - we should subclass JBDCPersistenceManager and the JBossMessage
                  //specific code in a subclass
                  
                  byte type = rs.getByte(9);
                  
                  Message m;
                  
                  if (type != CoreMessage.TYPE)
                  {
                     //JBossMessage
                     String jmsType = rs.getString(10);
                     String correlationID = rs.getString(11);
                     byte[] correlationIDBytes = rs.getBytes(12);
                     String destination = rs.getString(13);
                     String replyTo = rs.getString(14);
                     boolean replyToExists = !rs.wasNull();
                     bytes = getBytes(rs, 15);
                     HashMap jmsProperties = bytesToMap(bytes);
                     
                     JBossDestination dest;
                     if (destination.charAt(0) == 'Q')
                     {
                        dest = new JBossQueue(destination.substring(1, destination.length()));
                     }
                     else
                     {
                        dest = new JBossTopic(destination.substring(1, destination.length()));
                     }
                     
                     JBossDestination replyToDest = null;
                     
                     if (replyToExists)
                     {
                        if (replyTo.charAt(0) == 'Q')
                        {
                           replyToDest = new JBossQueue(replyTo.substring(1, replyTo.length()));
                        }
                        else
                        {
                           replyToDest = new JBossTopic(replyTo.substring(1, replyTo.length()));
                        }
                     }
                         
                     m = MessageFactory.createJBossMessage(messageId, reliable, expiration, timestamp, priority,
                                                           coreHeaders, payload, persistentChannelCount,
                                                           type, jmsType, correlationID, correlationIDBytes,
                                                           dest, replyToDest,
                                                           jmsProperties);
                  }
                  else
                  {
                     //Core message
                     m = MessageFactory.createCoreMessage(messageId, reliable, expiration, timestamp, priority,
                           coreHeaders, payload, persistentChannelCount);
                  }
                  
                  msgs.add(m);
               }
               
               rs.close();
               rs = null;
               
               ps.close();
               ps = null;
            }
         }
         
         if (trace)
         {
            log.trace("Loaded " + msgs.size() + " messages in total");
         }

         return msgs;
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (rs != null)
         {
            try
            {
               rs.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (ps != null)
         {
            try
            {
               ps.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         wrap.end();
      }
   }  
               
   public void addReferences(long channelID, List references) throws Exception
   {  
      Connection conn = null;
      PreparedStatement psInsertReference = null;  
      PreparedStatement psInsertMessage = null;
      PreparedStatement psUpdateMessage = null;
      TransactionWrapper wrap = new TransactionWrapper();
            
      //First we order the references in message order
      orderReferences(references);
      
      List addsToReverse = new ArrayList();
                        
      try
      {
         //Now we get a lock on all the messages. Since we have ordered the refs we should avoid deadlock
         getLocks(references);
         
         conn = ds.getConnection();
         
         Iterator iter = references.iterator();
         
         boolean messageInsertsInBatch = false;
         boolean messageUpdatesInBatch = false;
         
         if (usingBatchUpdates)
         {
            psInsertReference = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE_REF"));
            psInsertMessage = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE"));
            psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
         }
         
         while (iter.hasNext())
         {
            //We may need to persist the message itself 
            MessageReference ref = (MessageReference) iter.next();
                                            
            //For non reliable refs we insert the ref (and maybe the message) itself
                           
            if (!usingBatchUpdates)
            {
               psInsertReference = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE_REF"));
            }
            
            //Now store the reference
            addReference(channelID, ref, psInsertReference);
                        
            if (usingBatchUpdates)
            {
               psInsertReference.addBatch();
            }
            else
            {
               int rows = psInsertReference.executeUpdate();
               
               if (trace)
               {
                  log.trace("Inserted " + rows + " rows");
               }
               
               psInsertReference.close();
               psInsertReference = null;
            }
            
            if (!usingBatchUpdates)
            {
               psInsertMessage = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE"));
               psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
            }
                                                                                     
            //Maybe we need to persist the message itself
            Message m = ref.getMessage();
            
            m.incPersistentChannelCount();
            
            addsToReverse.add(ref);
  
            boolean added;
            if (m.getPersistentChannelCount() == 1)
            {
               //Hasn't been persisted before so need to persist the message
               storeMessage(m, psInsertMessage);
               
               added = true;
            }
            else
            {
               //Update the message with the new channel count
               updateMessageChannelCount(m, psUpdateMessage);
               
               added = false;
            }
            
            if (usingBatchUpdates)
            {
               if (added)
               {
                  psInsertMessage.addBatch();
                  messageInsertsInBatch = true;
               }
               else
               {
                  psUpdateMessage.addBatch();
                  messageUpdatesInBatch = true;
               }
            }
            else
            {
               if (added)
               {
                  int rows = psInsertMessage.executeUpdate();
                                      
                  if (trace)
                  {
                     log.trace("Inserted " + rows + " rows");
                  }
               }
               else
               {
                  int rows = psUpdateMessage.executeUpdate();
                 
                  if (trace)
                  {
                     log.trace("Updated " + rows + " rows");
                  }
               }
               psInsertMessage.close();
               psInsertMessage = null;
               psUpdateMessage.close();
               psUpdateMessage = null;
            }      
         }         
         
         if (usingBatchUpdates)
         {
            int[] rowsReference = psInsertReference.executeBatch();
            
            if (trace)
            {
               logBatchUpdate(getSQLStatement("INSERT_MESSAGE_REF"), rowsReference, "inserted");
            }
            
            if (messageInsertsInBatch)
            {
               int[] rowsMessage = psInsertMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("INSERT_MESSAGE"), rowsMessage, "inserted");
               }
            }
            if (messageUpdatesInBatch)
            {
               int[] rowsMessage = psUpdateMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"), rowsMessage, "updated");
               }
            }
            
            psInsertReference.close();
            psInsertReference = null;
            psInsertMessage.close();
            psInsertMessage = null;
            psUpdateMessage.close();
            psUpdateMessage = null;
         }         
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (psInsertReference != null)
         {
            try
            {
               psInsertReference.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psInsertMessage != null)
         {
            try
            {
               psInsertMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psUpdateMessage != null)
         {
            try
            {
               psUpdateMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
               //dwtwsbtwotl
            }
            catch (Throwable t)
            {
            }
         }
         try
         {
            wrap.end();                       
         }
         finally
         {            
            if (wrap.isFailed())
            {
               //Reverse the incs
               this.decPersistentCounts(addsToReverse);
            }
            
            //And then release locks
            this.releaseLocks(references);
         }         
      }      
   }
         
   public void removeReferences(long channelID, List references) throws Exception
   {
      if (trace) { log.trace(this + " Removing " + references.size() + " refs from channel " + channelID); }
          
      Connection conn = null;
      PreparedStatement psDeleteReference = null;  
      PreparedStatement psDeleteMessage = null;
      PreparedStatement psUpdateMessage = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      //We order the references
      orderReferences(references);
      
      List removesToReverse = new ArrayList();
           
      try
      {
         //We get locks on all the messages - since they are ordered we avoid deadlock
         getLocks(references);
         
         conn = ds.getConnection();
         
         Iterator iter = references.iterator();
         
         boolean messageDeletionsInBatch = false;
         boolean messageUpdatesInBatch = false;
         
         if (usingBatchUpdates)
         {
            psDeleteReference = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE_REF"));
            psDeleteMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
            psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
         }
         
         while (iter.hasNext())
         {
            MessageReference ref = (MessageReference) iter.next();
                                                             
            if (!usingBatchUpdates)
            {
               psDeleteReference = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE_REF"));
            }
            
            removeReference(channelID, ref, psDeleteReference);
            
            if (usingBatchUpdates)
            {
               psDeleteReference.addBatch();
            }
            else
            {
               int rows = psDeleteReference.executeUpdate();
               
               if (trace)
               {
                  log.trace("Deleted " + rows + " rows");
               }
               
               psDeleteReference.close();
               psDeleteReference = null;
            }
            
            if (!usingBatchUpdates)
            {
               psDeleteMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
               psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
            }
               
            Message m = ref.getMessage();
                                    
            //Maybe we need to delete the message itself
            
            m.decPersistentChannelCount();
            
            removesToReverse.add(ref);
  
            boolean removed;
            if (m.getPersistentChannelCount() == 0)
            {
               //No more refs so remove the message
               removeMessage(m, psDeleteMessage);
               
               removed = true;
            }
            else
            {
               //Update the message with the new channel count
               updateMessageChannelCount(m, psUpdateMessage);
               
               removed = false;
            }
            
            if (usingBatchUpdates)
            {
               if (removed)
               {
                  psDeleteMessage.addBatch();
                  messageDeletionsInBatch = true;
               }
               else
               {
                  psUpdateMessage.addBatch();
                  messageUpdatesInBatch = true;
               }
            }
            else
            {
               if (removed)
               {
                  int rows = psDeleteMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Deleted " + rows + " rows");
                  }
               }
               else
               {
                  int rows = psUpdateMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Updated " + rows + " rows");
                  }
               }
               psDeleteMessage.close();
               psDeleteMessage = null;
               psUpdateMessage.close();
               psUpdateMessage = null;
            }      
         }         
         
         if (usingBatchUpdates)
         {
            int[] rowsReference = psDeleteReference.executeBatch();
            
            if (trace)
            {
               logBatchUpdate(getSQLStatement("DELETE_MESSAGE_REF"), rowsReference, "deleted");
            }
            
            if (messageDeletionsInBatch)
            {
               int[] rowsMessage = psDeleteMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("DELETE_MESSAGE"), rowsMessage, "deleted");
               }
            }
            if (messageUpdatesInBatch)
            {
               int[] rowsMessage = psUpdateMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"), rowsMessage, "updated");
               }
            }
            
            psDeleteReference.close();
            psDeleteReference = null;
            psDeleteMessage.close();
            psDeleteMessage = null;
            psUpdateMessage.close();
            psUpdateMessage = null;
         }              
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (psDeleteReference != null)
         {
            try
            {
               psDeleteReference.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psDeleteMessage != null)
         {
            try
            {
               psDeleteMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psUpdateMessage != null)
         {
            try
            {
               psUpdateMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable t)
            {
            }
         }
         try
         {
            wrap.end();
         }
         finally
         {     
            if (wrap.isFailed())
            {
               //Reverse the decs
               this.incPersistentCounts(removesToReverse);
            }
            
            //And then release locks
            this.releaseLocks(references);
         }         
      }      
   }
      
   public List getPagedReferenceInfos(long channelID, long orderStart, long number) throws Exception
   {
      if (trace)
      {
         log.trace("loading message reference info for channel " + channelID + " from " + orderStart + " number " + number);
      }
                    
      List refs = new ArrayList();
      
      Connection conn = null;
      PreparedStatement ps = null;
      ResultSet rs = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      try
      {
         conn = ds.getConnection();
         
         ps = conn.prepareStatement(getSQLStatement("LOAD_PAGED_REFS"));
         
         ps.setLong(1, channelID);
         
         ps.setLong(2, orderStart);
         
         ps.setLong(3, orderStart + number - 1);
         
         rs = ps.executeQuery();
         
         long ord = orderStart;
         
         while (rs.next())
         {
            long msgId = rs.getLong(1);     
            int deliveryCount = rs.getInt(2);
            int pageOrd = rs.getInt(3);
            boolean reliable = rs.getString(4).equals("Y");
            
            //Sanity check
            if (pageOrd != ord)
            {
               throw new IllegalStateException("Unexpected pageOrd: " + pageOrd + " expected: " + ord);
            }
            
            ReferenceInfo ri = new ReferenceInfo(msgId, deliveryCount, reliable);
            
            refs.add(ri);
            ord++;
         }
         
         //Sanity check
         if (ord != orderStart + number)
         {
            throw new IllegalStateException("Didn't load expected number of references, loaded: " + (ord - orderStart) +
                                            " expected: " + number);
         }
         
         return refs;
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (rs != null)
         {
            try
            {
               rs.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (ps != null)
         {
            try
            {
               ps.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         wrap.end();
      }      
   }   
   
   public InitialLoadInfo getInitialReferenceInfos(long channelID, int fullSize) throws Exception
   {
      if (trace)
      {
         log.trace("loading initial reference infos for channel " + channelID);
      }
                    
      List refs = new ArrayList();
      List extraRefs = new ArrayList();
      
      Connection conn = null;
      PreparedStatement ps = null;
      ResultSet rs = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      try
      {
         conn = ds.getConnection();         
         
         //First we get the values for min() and max() page order
         ps = conn.prepareStatement(getSQLStatement("SELECT_MIN_MAX_PAGE_ORD"));
         
         ps.setLong(1, channelID);
         
         rs = ps.executeQuery();
                  
         rs.next();
         
         long minOrdering = rs.getLong(1);
         
         if (rs.wasNull())
         {
            minOrdering = -1;
         }
         
         long maxOrdering = rs.getLong(2);
         
         if (rs.wasNull())
         {
            maxOrdering = -1;
         }
         
         //Must cope with the possibility that fullSize has changed for the channel since last
         //restart
         
         conn = ds.getConnection();
         
         ps = conn.prepareStatement(getSQLStatement("LOAD_UNPAGED_REFS"));
         
         ps.setLong(1, channelID);
                 
         rs = ps.executeQuery();
         
         int count = 0;
         while (rs.next())
         {
            long msgId = rs.getLong(1);            
            int deliveryCount = rs.getInt(2);
            boolean reliable = rs.getString(3).equals("Y");
            
            ReferenceInfo ri = new ReferenceInfo(msgId, deliveryCount, reliable);
            
            if (count < fullSize)
            {
               refs.add(ri);
            }
            else
            {
               extraRefs.add(ri);
            }
            
            count++;
         }
                  
         if (count != fullSize)
         {                        
            rs.close();
            rs = null;
            ps.close();
            ps = null;            
            
            if (count < fullSize)
            {
               if (minOrdering != -1)
               {
                  //This means that fullSize is now greater and we have some paged refs
                  //We need to convert some of the paged refs into non paged refs before loading               
                  
                  //We can do this by setting page_ord = null for the first x paged refs                  
                  
                  long numberPaged = 1 + maxOrdering - minOrdering;
                  
                  long numberToConvert = Math.min(numberPaged, fullSize - count);
                                    
                  ps = conn.prepareStatement(getSQLStatement("UPDATE_RELIABLE_REFS_NOT_PAGED"));
                  
                  ps.setLong(1, minOrdering);
                  ps.setLong(2, minOrdering + numberToConvert - 1);
                  ps.setLong(3, channelID);
                  
                  ps.executeUpdate();
                  
                  minOrdering += numberToConvert;      
                  
                  if (minOrdering == maxOrdering + 1)
                  {
                     minOrdering = maxOrdering = -1;
                  }
                  
                  ps.close();
                  ps = null;
  
                  //Need to reload
                  
                  ps = conn.prepareStatement(getSQLStatement("LOAD_UNPAGED_REFS"));
                  
                  ps.setLong(1, channelID);
                          
                  rs = ps.executeQuery();
                  
                  refs.clear();
                  
                  while (rs.next())
                  {
                     long msgId = rs.getLong(1);            
                     int deliveryCount = rs.getInt(2);
                     boolean reliable = rs.getString(3).equals("Y");                  
                     ReferenceInfo ri = new ReferenceInfo(msgId, deliveryCount, reliable);
                     refs.add(ri);                  
                  }                                           
               }
               else
               {
                  //Not a problem nothing to do
               }
            }
            else if (count > fullSize)
            {         
               //This means that fullSize is now smaller
               //We need to convert some of the non paged refs into paged refs before loading
               
               int numberToConvert = count - fullSize;
               
               //Shift any pre-existing paged refs up by this
               
               ps = conn.prepareStatement(getSQLStatement("SHIFT_PAGE_ORDER"));
               
               ps.setLong(1, numberToConvert);
               
               ps.setLong(2, channelID);
               
               ps.executeUpdate();
               
               //Now we need to convert the last <numberToConvert> non paged refs to paged refs
               ps.close();
               ps = null;
               
               ps = conn.prepareStatement(getSQLStatement("UPDATE_PAGE_ORDER"));
                                             
               long c;
               if (minOrdering == -1)
               {
                  c = 0;
                  minOrdering = 0;
               }
               else
               {
                  c = minOrdering;
               }
               
               if (maxOrdering == -1)
               {
                  maxOrdering = numberToConvert - 1;
               }
               else
               {
                  maxOrdering += numberToConvert;
               }
               
               Iterator iter = extraRefs.iterator();
               while (iter.hasNext())
               {
                  ReferenceInfo ri = (ReferenceInfo)iter.next();
                  
                  ps.setLong(1, c);
                  ps.setLong(2, ri.getMessageId());
                  ps.setLong(3, channelID);
                  
                  //TODO use batch updates
                  ps.executeUpdate();
                  
                  c++;
               }               
            }
         }
         
         return new InitialLoadInfo(minOrdering, maxOrdering, refs);
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (rs != null)
         {
            try
            {
               rs.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (ps != null)
         {
            try
            {
               ps.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         wrap.end();
      }      
   }   
   
   public void updatePageOrder(long channelID, List references) throws Exception
   {
      Connection conn = null;
      PreparedStatement psUpdateReference = null;  
      TransactionWrapper wrap = new TransactionWrapper();
      
      if (trace)
      {
         log.trace("Updating page order for channel:" + channelID);
      }
        
      try
      {
         conn = ds.getConnection();
         
         Iterator iter = references.iterator();
         
         if (usingBatchUpdates)
         {
            psUpdateReference = conn.prepareStatement(getSQLStatement("UPDATE_PAGE_ORDER"));
         }
         
         while (iter.hasNext())
         {
            MessageReference ref = (MessageReference) iter.next();
                 
            if (!usingBatchUpdates)
            {
               psUpdateReference = conn.prepareStatement(getSQLStatement("UPDATE_PAGE_ORDER"));
            }
            
            psUpdateReference.setLong(1, ref.getPagingOrder());
            
            psUpdateReference.setLong(2, ref.getMessageID());
            
            psUpdateReference.setLong(3, channelID);
            
            if (usingBatchUpdates)
            {
               psUpdateReference.addBatch();
            }
            else
            {
               int rows = psUpdateReference.executeUpdate();
               
               if (trace)
               {
                  log.trace("Updated " + rows + " rows");
               }
               
               psUpdateReference.close();
               psUpdateReference = null;
            }
         }
                     
         if (usingBatchUpdates)
         {
            int[] rowsReference = psUpdateReference.executeBatch();
            
            if (trace)
            {
               logBatchUpdate(getSQLStatement("UPDATE_PAGE_ORDER"), rowsReference, "updated");
            }
                        
            psUpdateReference.close();
            psUpdateReference = null;
         }
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (psUpdateReference != null)
         {
            try
            {
               psUpdateReference.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable t)
            {
            }
         }
         wrap.end();
      }    
   }
   
   // End of paging functionality
   // ===========================
   
   public void addReference(long channelID, MessageReference ref, Transaction tx) throws Exception
   {      
      if (tx != null)
      {
         //In a tx so we just add the ref in the tx in memory for now

         TransactionCallback callback = getCallback(tx);

         callback.addReferenceToAdd(channelID, ref);
      }
      else
      {         
         //No tx so add the ref directly in the db
         
         TransactionWrapper wrap = new TransactionWrapper();
         
         PreparedStatement psReference = null;
         PreparedStatement psMessage = null;
         
         Connection conn = ds.getConnection();
         
         Message m = ref.getMessage();     
         
         boolean incremented = false;
         
         try
         {            
            // Get lock on message
            LockMap.instance.obtainLock(m);
                                    
            psReference = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE_REF"));
            
            // Add the reference
            addReference(channelID, ref, psReference);
            
            int rows = psReference.executeUpdate();            
            
            if (trace)
            {
               log.trace("Inserted " + rows + " rows");
            }
            
            m.incPersistentChannelCount();
            incremented = true;
                           
            if (m.getPersistentChannelCount() == 1)
            {
               // First time so persist the message
               psMessage = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE"));
               
               storeMessage(m, psMessage);        
            }
            else
            {
               //Update the message's channel count
               psMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
               
               updateMessageChannelCount(m, psMessage);
            }
                           
            rows = psMessage.executeUpdate();
            if (trace)
            {
               log.trace("Inserted/updated " + rows + " rows");
            }                                     
         }
         catch (Exception e)
         {
            wrap.exceptionOccurred();
            throw e;
         }
         finally
         {
            if (psReference != null)
            {
               try
               {
                  psReference.close();
               }
               catch (Throwable t)
               {
               }
            }
            if (psMessage != null)
            {
               try
               {
                  psMessage.close();
               }
               catch (Throwable t)
               {
               }
            }
            if (conn != null)
            {
               try
               {
                  conn.close();
               }
               catch (Throwable t)
               {
               }
            }
            try
            {
               wrap.end();
            }
            finally
            {   
               if (wrap.isFailed() && incremented)
               {
                  //reverse the inc                  
                  m.decPersistentChannelCount();                  
               }
               
               //Release Lock
               LockMap.instance.releaseLock(m);
            }
         }      
      }
   }
   
   public void removeReference(long channelID, MessageReference ref, Transaction tx) throws Exception
   {      
      if (tx != null)
      {
         //In a tx so we just add the ref in the tx in memory for now

         TransactionCallback callback = getCallback(tx);

         callback.addReferenceToRemove(channelID, ref);
      }
      else
      {         
         //No tx so we remove the reference directly from the db
         
         TransactionWrapper wrap = new TransactionWrapper();
         
         PreparedStatement psReference = null;
         PreparedStatement psMessage = null;
         
         Connection conn = ds.getConnection();
         
         Message m = ref.getMessage();         
         
         boolean decremented = false;
         
         try
         {
            //get lock on message
            LockMap.instance.obtainLock(m);
                              
            psReference = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE_REF"));
            
            //Remove the message reference
            removeReference(channelID, ref, psReference);
            
            int rows = psReference.executeUpdate();
            
            if (rows != 1)
            {
               throw new IllegalStateException("Failed to remove row for: " + ref);
            }
            
            if (trace)
            {
               log.trace("Deleted " + rows + " rows");
            }
            
            m.decPersistentChannelCount();
            decremented = true;
                           
            if (m.getPersistentChannelCount() == 0)
            {
               //No other channels have a reference so we can delete the message
               psMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
                                 
               removeMessage(m, psMessage);
            }
            else
            {
               //Other channel(s) still have hold references so update the channel count
               psMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
               
               updateMessageChannelCount(m, psMessage);
            }
                              
            rows = psMessage.executeUpdate();
            
            if (rows != 1)
            {
               throw new IllegalStateException("Failed to update/delete message row for ref " + ref);
            }
            
            if (trace)
            {
               log.trace("Delete/updated " + rows + " rows");
            }                           
         }
         catch (Exception e)
         {
            wrap.exceptionOccurred();
            throw e;
         }
         finally
         {
            if (psReference != null)
            {
               try
               {
                  psReference.close();
               }
               catch (Throwable t)
               {
               }
            }
            if (psMessage != null)
            {
               try
               {
                  psMessage.close();
               }
               catch (Throwable t)
               {
               }
            }
            if (conn != null)
            {
               try
               {
                  conn.close();
               }
               catch (Throwable t)
               {
               }
            }
            try
            {
               wrap.end();               
            }
            finally
            {      
               if (wrap.isFailed() && decremented)
               {
                  //Reverse decrement
                  m.incPersistentChannelCount();
               }
               //release the lock
               LockMap.instance.releaseLock(m);
            }
         }      
      }
   }
   
//   public void resetPagedStatus(long channelID) throws Exception
//   {
//      if (trace) { log.trace("resetting paged status for channel " + channelID); }
//      
//      Connection conn = null;
//      PreparedStatement ps = null;
//      TransactionWrapper wrap = new TransactionWrapper();
//      
//      log.trace("Resetting paged status. This may take several minutes for large queues/subscriptions...");
//      
//      try
//      {
//         conn = ds.getConnection();
//         
//         log.debug("Updating all page ordering to null");
//                  
//         ps = conn.prepareStatement(getSQLStatement("RESET_PAGED_STATUS"));
//         
//         ps.setLong(1, channelID);
//         
//         int rows = ps.executeUpdate();
//         
//         if (trace)
//         {
//            log.trace(JDBCUtil.statementToString(getSQLStatement("RESET_PAGED_STATUS"))
//                  + " updated " + rows + " rows");
//         }
//               
//         ps.close();
//         
//      }
//      catch (Exception e)
//      {
//         wrap.exceptionOccurred();
//         throw e;
//      }
//      finally
//      {
//         if (ps != null)
//         {
//            try
//            {
//               ps.close();
//            }
//            catch (Throwable e)
//            {
//            }
//         }
//         if (conn != null)
//         {
//            try
//            {
//               conn.close();
//            }
//            catch (Throwable e)
//            {
//            }
//         }
//         wrap.end();
//      }
//   }
     
   public List retrievePreparedTransactions() throws Exception
   {
      Connection conn = null;
      Statement st = null;
      ResultSet rs = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      try
      {
         List transactions = new ArrayList();
         
         conn = ds.getConnection();
         
         st = conn.createStatement();
         rs = st.executeQuery(getSQLStatement("SELECT_PREPARED_TRANSACTIONS"));
         
         while (rs.next())
         {
            byte[] branchQual = rs.getBytes(2);
            int formatId = rs.getInt(3);
            byte[] globalTxId = rs.getBytes(4);
            Xid xid = new XidImpl(branchQual, formatId, globalTxId);
            
            transactions.add(xid);
         }
         
         return transactions;
         
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (rs != null)
         {
            try
            {
               rs.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (st != null)
         {
            try
            {
               st.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         wrap.end();
      }
   }
  
   // Public --------------------------------------------------------
   
   /**
    * Managed attribute.
    */
   public boolean isUsingBatchUpdates() throws Exception
   {
      return usingBatchUpdates;
   }
   
   /**
    * Managed attribute.
    */
   public void setUsingBatchUpdates(boolean b) throws Exception
   {
      usingBatchUpdates = b;
   }
   
   public int getMaxParams()
   {
      return maxParams;
   }
   
   public void setMaxParams(int maxParams)
   {
      this.maxParams = maxParams;
   }
   
   public String toString()
   {
      return "JDBCPersistenceManager[" + Integer.toHexString(hashCode()) + "]";
   }
   
   // Public --------------------------------------------------------
   
   // Package protected ---------------------------------------------
   
   // Protected -----------------------------------------------------
      
   protected TransactionCallback getCallback(Transaction tx)
   {
      TransactionCallback callback = (TransactionCallback) tx.getKeyedCallback(this);

      if (callback == null)
      {
         callback = new TransactionCallback(tx);

         tx.addKeyedCallback(callback, this);
      }

      return callback;
   }
   
   /*
    * We order the list of references in ascending message order    
    * thus preventing deadlock when 2 or more channels are updating the same messages in different transactions.   
    */
   protected void orderReferences(List references)
   {      
      Collections.sort(references, MessageOrderComparator.instance);
   }
   
   /*
    *
    * We want to remove any non reliable refs from the database and any corresponding messages if their channel count
    * has gone to zero
    * 
    * TODO
    * Really - this method only needs to be executed on start up if the server has crashed
    * We should save a flag in the db at server shutdown and check for this at startup to see if there
    * was a clean shutdown
    * 
    */
   protected void removeUnreliableMessageData() throws Exception
   {
      log.trace("Removing all non-persistent data");
      
      Connection conn = null;
      PreparedStatement psRes = null;
      PreparedStatement psUpdate = null;
      PreparedStatement psDeleteMsgs = null;
      PreparedStatement psDeleteRefs = null;
      TransactionWrapper wrap = new TransactionWrapper();
          
      ResultSet rs = null;
      
      try
      {
         conn = ds.getConnection();
         
         psRes = conn.prepareStatement(getSQLStatement("SELECT_ALL_CHANNELS"));
         
         psUpdate = conn.prepareStatement(getSQLStatement("UPDATE_UNRELIABLE_CHANNELCOUNT"));
                          
         rs = psRes.executeQuery();
         
         while (rs.next())
         {
            long channelID = rs.getLong(1);
            
            psUpdate.setLong(1, channelID);
            
            int rows = psUpdate.executeUpdate();
            
            if (trace)
            {
               log.trace(JDBCUtil.statementToString(getSQLStatement("UPDATE_UNRELIABLE_CHANNELCOUNT"))
                     + " updated " + rows + " rows");
            }            
         }
         
         psDeleteRefs = conn.prepareStatement(getSQLStatement("DELETE_UNRELIABLE_REFS"));
         
         int rows = psDeleteRefs.executeUpdate();
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("DELETE_UNRELIABLE_REFS"))
                  + " deleted " + rows + " rows");
         }
                  
         psDeleteMsgs = conn.prepareStatement(getSQLStatement("DELETE_UNREFFED_MESSAGES"));
         
         rows = psDeleteMsgs.executeUpdate();
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("DELETE_UNREFFED_MESSAGES"))
                  + " deleted " + rows + " rows");
         }                   
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (rs != null)
         {
            try
            {
               rs.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (psRes != null)
         {
            try
            {
               psRes.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (psUpdate != null)
         {
            try
            {
               psUpdate.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (psDeleteMsgs != null)
         {
            try
            {
               psDeleteMsgs.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (psDeleteRefs != null)
         {
            try
            {
               psDeleteRefs.close();
            }
            catch (Throwable e)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         wrap.end();
      }
   }
   
   
   protected void handleBeforeCommit1PC(List refsToAdd, List refsToRemove, Transaction tx)
      throws Exception
   {
      //TODO - A slight optimisation - it's possible we have refs referring to the same message
      //so we will end up acquiring the lock more than once which is unnecessary
      //If find unique set of messages can avoid this
      List allRefs = new ArrayList(refsToAdd.size() + refsToRemove.size());
      Iterator iter = refsToAdd.iterator();
      while (iter.hasNext())
      {
         ChannelRefPair pair = (ChannelRefPair)iter.next();
         allRefs.add(pair.ref);
      }
      iter = refsToRemove.iterator();
      while (iter.hasNext())
      {
         ChannelRefPair pair = (ChannelRefPair)iter.next();
         allRefs.add(pair.ref);
      }
            
      orderReferences(allRefs);
      
      //For one phase we simply add rows corresponding to the refs
      //and remove rows corresponding to the deliveries in one jdbc tx
      //We also need to store or remove messages as necessary, depending
      //on whether they've already been stored or still referenced by other
      //channels
         
      Connection conn = null;
      PreparedStatement psReference = null;
      PreparedStatement psInsertMessage = null;
      PreparedStatement psUpdateMessage = null;
      PreparedStatement psDeleteMessage = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      List addsToReverse = new ArrayList();
      List removesToReverse = new ArrayList();
      
      try
      {
         conn = ds.getConnection();
         
         //obtain locks on all messages
         getLocks(allRefs);
         
         //First the adds
         
         iter = refsToAdd.iterator();
         
         boolean batch = usingBatchUpdates && refsToAdd.size() > 0;
         
         boolean messageInsertsInBatch = false;
         boolean messageUpdatesInBatch = false;
         
         if (batch)
         {
            psReference = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE_REF"));
            psInsertMessage = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE"));
            psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
         }
         
         while (iter.hasNext())
         {
            ChannelRefPair pair = (ChannelRefPair) iter.next();
            
            MessageReference ref = pair.ref;
                                                
            if (!batch)
            {
               psReference = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE_REF"));
            }
            
            //Now store the reference
            addReference(pair.channelId, ref, psReference);
              
            if (batch)
            {
               psReference.addBatch();
            }
            else
            {
               int rows = psReference.executeUpdate();
               
               if (trace)
               {
                  log.trace("Inserted " + rows + " rows");
               }
               
               psReference.close();
               psReference = null;
            }
            
            Message m = ref.getMessage();        
            
            if (!batch)
            {
               psInsertMessage = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE"));
               psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
            }
                         
            m.incPersistentChannelCount();
            addsToReverse.add(ref);
            
            boolean added;
            if (m.getPersistentChannelCount() == 1)
            {
               //First time so add message
               storeMessage(m, psInsertMessage);

               added = true;
            }
            else
            {
               //Update message channel count
               updateMessageChannelCount(m, psUpdateMessage);
 
               added = false;
            }
            
            if (batch)
            {
               if (added)
               {
                  psInsertMessage.addBatch();
                  messageInsertsInBatch = true;
               }
               else
               {
                  psUpdateMessage.addBatch();
                  messageUpdatesInBatch = true;
               }
            }
            else
            {
               if (added)
               {
                  int rows = psInsertMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Inserted " + rows + " rows");
                  }
               }
               else
               {
                  int rows = psUpdateMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Updated " + rows + " rows");
                  }
               }
               psInsertMessage.close();
               psInsertMessage = null;
               psUpdateMessage.close();
               psUpdateMessage = null;
            }
         }         
         
         if (batch)
         {
            int[] rowsReference = psReference.executeBatch();
            
            if (trace)
            {
               logBatchUpdate(getSQLStatement("INSERT_MESSAGE_REF"), rowsReference, "inserted");
            }
            
            if (messageInsertsInBatch)
            {
               int[] rowsMessage = psInsertMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("INSERT_MESSAGE"), rowsMessage, "inserted");
               }
            }
            if (messageUpdatesInBatch)
            {
               int[] rowsMessage = psUpdateMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"), rowsMessage, "updated");
               }
            }
            
            psReference.close();
            psReference = null;
            psInsertMessage.close();
            psInsertMessage = null;
            psUpdateMessage.close();
            psUpdateMessage = null;
         }
         
         //Now the removes
         
         iter = refsToRemove.iterator();
         
         batch = usingBatchUpdates && refsToRemove.size() > 0;
         boolean messageDeletionsInBatch = false;
         messageUpdatesInBatch = false;
         psReference = null;
         psUpdateMessage = null;
         psDeleteMessage = null;
         
         if (batch)
         {
            psReference = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE_REF"));
            psDeleteMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
            psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
         }
         
         while (iter.hasNext())
         {
            ChannelRefPair pair = (ChannelRefPair) iter.next();
            
            if (!batch)
            {
               psReference = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE_REF"));
            }
            
            removeReference(pair.channelId, pair.ref, psReference);
            
            if (batch)
            {
               psReference.addBatch();
            }
            else
            {
               int rows = psReference.executeUpdate();
               
               if (trace)
               {
                  log.trace("Deleted " + rows + " rows");
               }
               
               psReference.close();
               psReference = null;
            }
            
            if (!batch)
            {
               psDeleteMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
               psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
            }
            
            Message m = pair.ref.getMessage();
                                
            m.decPersistentChannelCount();
            
            removesToReverse.add(pair.ref);
            
            boolean removed;
            
            if (m.getPersistentChannelCount() == 0)
            {
               //No more refs - message can be deleted
               removeMessage(m, psDeleteMessage);
                  
               removed = true;
            }
            else
            {
               //Update channel count for message
               updateMessageChannelCount(m, psUpdateMessage);
               
               removed = false;
            }
            
            if (batch)
            {
               if (removed)
               {
                  psDeleteMessage.addBatch();
                  messageDeletionsInBatch = true;
               }
               else
               {
                  psUpdateMessage.addBatch();
                  messageUpdatesInBatch = true;                        
               }
            }
            else
            {
               if (removed)
               {
                  int rows = psDeleteMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Deleted " + rows + " rows");
                  }
               }
               else
               {
                  int rows = psUpdateMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Updated " + rows + " rows");
                  }
               }
               psDeleteMessage.close();
               psDeleteMessage = null;
               psUpdateMessage.close();
               psUpdateMessage = null;
            }            
         }
         
         if (batch)
         {
            int[] rowsReference = psReference.executeBatch();
            
            if (trace)
            {
               logBatchUpdate(getSQLStatement("DELETE_MESSAGE_REF"), rowsReference, "deleted");
            }
            
            if (messageDeletionsInBatch)
            {
               int[] rowsMessage = psDeleteMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("DELETE_MESSAGE"), rowsMessage, "deleted");
               }
            }
            if (messageUpdatesInBatch)
            {
               int[] rowsMessage = psUpdateMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"), rowsMessage, "updated");
               }
            }
            
            psReference.close();
            psReference = null;
            psDeleteMessage.close();
            psDeleteMessage = null;
            psUpdateMessage.close();
            psUpdateMessage = null;
         }         
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();                  
         
         throw e;
      }
      finally
      {
         if (psReference != null)
         {
            try
            {
               psReference.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psInsertMessage != null)
         {
            try
            {
               psInsertMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psUpdateMessage != null)
         {
            try
            {
               psUpdateMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psDeleteMessage != null)
         {
            try
            {
               psDeleteMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         try
         {
            wrap.end();                        
         }
         finally
         {
            if (wrap.isFailed())
            {
               //Reverse any incs and decs we made
               this.decPersistentCounts(addsToReverse);
               this.incPersistentCounts(removesToReverse);
            }
            
            //Release the locks
            this.releaseLocks(allRefs);
         }
      }
   }
   
   protected void handleBeforeCommit2PC(List refsToRemove, Transaction tx)
      throws Exception
   {          
      Connection conn = null;
      PreparedStatement psUpdateMessage = null;
      PreparedStatement psDeleteMessage = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      List refs = new ArrayList(refsToRemove.size());
      Iterator iter = refsToRemove.iterator();
      while (iter.hasNext())
      {
         ChannelRefPair pair = (ChannelRefPair)iter.next();
         refs.add(pair.ref);
      }
            
      orderReferences(refs);      
      
      List removesToReverse = new ArrayList();
      
      try
      {
         //get locks on all the refs
         this.getLocks(refs);
         
         conn = ds.getConnection();
                  
         //2PC commit
         
         //First we commit any refs in state "+" to "C" and delete any
         //refs in state "-", then we
         //remove any messages due to refs we just removed
         //if they're not referenced elsewhere
         
         commitPreparedTransaction(tx, conn);
         
         boolean batch = usingBatchUpdates && refsToRemove.size() > 0;
         boolean messageDeletionsInBatch = false;
         boolean messageUpdatesInBatch = false;
      
         if (batch)
         {
            psDeleteMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
            psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
         }
                  
         iter = refsToRemove.iterator();
         while (iter.hasNext())
         {
            ChannelRefPair pair = (ChannelRefPair) iter.next();
            
            MessageReference ref = pair.ref;
            
            if (!batch)
            {
               psDeleteMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
               psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
            }
            
            Message m = ref.getMessage();
                                   
            //We may need to remove the message itself
            
            m.decPersistentChannelCount();
            removesToReverse.add(ref);
            
            boolean removed;
            if (m.getPersistentChannelCount() == 0)
            {
               //We can remove the message
               removeMessage(m, psDeleteMessage);
               
               removed = true;
            }
            else
            {
               //Decrement channel count
               updateMessageChannelCount(m, psUpdateMessage);
               
               removed = false;
            }
                           
            if (batch)
            {
               if (removed)
               {
                  psDeleteMessage.addBatch();
                  
                  messageDeletionsInBatch = true;
               }
               else
               {
                  psUpdateMessage.addBatch();
                  
                  messageUpdatesInBatch = true;
               }
            }
            else
            {
               if (removed)
               {
                  int rows = psDeleteMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Deleted " + rows + " rows");
                  }
               }
               else
               {
                  int rows = psUpdateMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Updated " + rows + " rows");
                  }
               }
               psDeleteMessage.close();
               psDeleteMessage = null;
               psUpdateMessage.close();
               psUpdateMessage = null;
            }
         }         
         
         if (batch)
         {
            if (messageDeletionsInBatch)
            {
               int[] rows = psDeleteMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("DELETE_MESSAGE"), rows, "deleted");
               }
               
               psDeleteMessage.close();
               psDeleteMessage = null;
            }
            if (messageUpdatesInBatch)
            {
               int[] rows = psUpdateMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"), rows, "updated");
               }
               
               psUpdateMessage.close();
               psUpdateMessage = null;
            }
         }         
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (psDeleteMessage != null)
         {
            try
            {
               psDeleteMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psUpdateMessage != null)
         {
            try
            {
               psUpdateMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         try
         {
            wrap.end();
         }
         finally
         {
            if (wrap.isFailed())
            {
               //reverse any decs
               this.incPersistentCounts(removesToReverse);
            }
            //release the locks
            this.releaseLocks(refs);
         }
      }
   }
   
   protected void handleBeforePrepare(List refsToAdd, List refsToRemove, Transaction tx) throws Exception
   {
      //We only need to lock on the adds
      List refs = new ArrayList(refsToAdd.size());
      
      Iterator iter = refsToAdd.iterator();
      while (iter.hasNext())
      {
         ChannelRefPair pair = (ChannelRefPair)iter.next();
         
         refs.add(pair.ref);
      }
      
      orderReferences(refs);
      
      List addsToReverse = new ArrayList();
      
      //We insert a tx record and
      //a row for each ref with +
      //and update the row for each delivery with "-"
      
      PreparedStatement psReference = null;
      PreparedStatement psInsertMessage = null;
      PreparedStatement psUpdateMessage = null;
      Connection conn = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      try
      {
         //get the locks
         getLocks(refs);
         
         conn = ds.getConnection();
         
         //Insert the tx record
         if (!refsToAdd.isEmpty() || !refsToRemove.isEmpty())
         {
            addTXRecord(conn, tx);
         }
         
         iter = refsToAdd.iterator();
         
         boolean batch = usingBatchUpdates && refsToAdd.size() > 1;
         boolean messageInsertsInBatch = false;
         boolean messageUpdatesInBatch = false;
         if (batch)
         {
            psReference = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE_REF"));
            psInsertMessage = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE"));
            psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
         }
         
         while (iter.hasNext())
         {
            ChannelRefPair pair = (ChannelRefPair) iter.next();
            
            if (!batch)
            {
               psReference = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE_REF"));
            }
            
            prepareToAddReference(pair.channelId, pair.ref, tx, psReference);
            
            if (batch)
            {
               psReference.addBatch();
            }
            else
            {
               int rows = psReference.executeUpdate();
               
               if (trace)
               {
                  log.trace("Inserted " + rows + " rows");
               }
               
               psReference.close();
               psReference = null;
            }
            
            if (!batch)
            {
               psInsertMessage = conn.prepareStatement(getSQLStatement("INSERT_MESSAGE"));
               psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
            }
            
            Message m = pair.ref.getMessage();
            
            m.incPersistentChannelCount();
            addsToReverse.add(pair.ref);
                        
            boolean added;
            if (m.getPersistentChannelCount() == 1)
            {
               //First time so persist the message
               storeMessage(m, psInsertMessage);
               
               added = true;
            }
            else
            {
               //Update message channel count
               updateMessageChannelCount(m, psUpdateMessage);
               
               added = false;
            }
            
            if (batch)
            {
               if (added)
               {
                  psInsertMessage.addBatch();
                  messageInsertsInBatch = true;
               }
               else
               {
                  psUpdateMessage.addBatch();
                  messageUpdatesInBatch = true;
               }
            }
            else
            {
               if (added)
               {
                  int rows = psInsertMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Inserted " + rows + " rows");
                  }
               }
               else
               {
                  int rows = psUpdateMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("Updated " + rows + " rows");
                  }
               }
               psInsertMessage.close();
               psInsertMessage = null;
               psUpdateMessage.close();
               psUpdateMessage = null;
            }
         }         
         
         if (batch)
         {
            int[] rowsReference = psReference.executeBatch();
            
            if (trace)
            {
               logBatchUpdate(getSQLStatement("INSERT_MESSAGE_REF"), rowsReference, "inserted");
            }
            
            if (messageInsertsInBatch)
            {
               int[] rowsMessage = psInsertMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("INSERT_MESSAGE"), rowsMessage, "inserted");
               }
            }
            if (messageUpdatesInBatch)
            {
               int[] rowsMessage = psUpdateMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"), rowsMessage, "updated");
               }
            }
            
            psReference.close();
            psReference = null;
            psInsertMessage.close();
            psInsertMessage = null;
            psUpdateMessage.close();
            psUpdateMessage = null;
         }
         
         //Now the removes
         
         iter = refsToRemove.iterator();
         
         batch = usingBatchUpdates && refsToRemove.size() > 1;
         if (batch)
         {
            psReference = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_REF"));
         }
         
         while (iter.hasNext())
         {
            ChannelRefPair pair = (ChannelRefPair) iter.next();
            
            if (!batch)
            {
               psReference = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_REF"));
            }
            
            prepareToRemoveReference(pair.channelId, pair.ref, tx, psReference);
            
            if (batch)
            {
               psReference.addBatch();
            }
            else
            {
               int rows = psReference.executeUpdate();
               
               if (trace)
               {
                  log.trace("updated " + rows + " rows");
               }
               
               psReference.close();
               psReference = null;
            }
         }
         
         if (batch)
         {
            int[] rows = psReference.executeBatch();
            
            if (trace)
            {
               logBatchUpdate(getSQLStatement("UPDATE_MESSAGE_REF"), rows, "updated");
            }
            
            psReference.close();
            psReference = null;
         }
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (psReference != null)
         {
            try
            {
               psReference.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psInsertMessage != null)
         {
            try
            {
               psInsertMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psUpdateMessage != null)
         {
            try
            {
               psUpdateMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         try
         {
            wrap.end();            
         }
         finally
         {
            if (wrap.isFailed())
            {
               //reverse any incs
               this.decPersistentCounts(addsToReverse);
            }
            
            //release the locks
            
            this.releaseLocks(refs);
         }
      }
   }
   
   protected void handleBeforeRollback(List refsToAdd, Transaction tx) throws Exception
   {
      //remove refs marked with +
      //and update rows marked with - to C
            
      PreparedStatement psDeleteMessage = null;
      PreparedStatement psUpdateMessage = null;
      Connection conn = null;
      TransactionWrapper wrap = new TransactionWrapper();
      
      List refs = new ArrayList(refsToAdd.size());
      
      Iterator iter = refsToAdd.iterator();
      
      while (iter.hasNext())
      {
         ChannelRefPair pair = (ChannelRefPair)iter.next();
         refs.add(pair.ref);
      }
      
      orderReferences(refs);
      
      List removesToReverse = new ArrayList();
      
      try
      {
         this.getLocks(refs);
         
         conn = ds.getConnection();
         
         rollbackPreparedTransaction(tx, conn);
         
         iter = refsToAdd.iterator();
         
         boolean batch = usingBatchUpdates && refsToAdd.size() > 1;
         boolean messageDeletionsInBatch = false;
         boolean messageUpdatesInBatch = false;
         if (batch)
         {
            psDeleteMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
            psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
         }
                                 
         while (iter.hasNext())
         {
            ChannelRefPair pair = (ChannelRefPair) iter.next();
            
            if (!batch)
            {
               psDeleteMessage = conn.prepareStatement(getSQLStatement("DELETE_MESSAGE"));
               psUpdateMessage = conn.prepareStatement(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"));
            }
            
            Message m = pair.ref.getMessage();
                                         
            //We may need to remove the message for messages added during the prepare stage
                        
            m.decPersistentChannelCount();
            removesToReverse.add(pair.ref);
            
            boolean removed;
            if (m.getPersistentChannelCount() == 0)
            {
               //remove message
               removeMessage(m, psDeleteMessage);
               
               removed = true;                    
            }
            else
            {
               //update message channel count
               updateMessageChannelCount(m, psUpdateMessage);
               
               removed = false;
            }
                              
            if (batch)
            {
               if (removed)
               {
                  psDeleteMessage.addBatch();
                  messageDeletionsInBatch = true;
               }
               else
               {
                  psUpdateMessage.addBatch();
                  messageUpdatesInBatch = true;
               }
            }
            else
            {
               if (removed)
               {
                  int rows = psDeleteMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("deleted " + rows + " rows");
                  }
               }
               else
               {
                  int rows = psUpdateMessage.executeUpdate();
                  
                  if (trace)
                  {
                     log.trace("updated " + rows + " rows");
                  }
               }
               psDeleteMessage.close();
               psDeleteMessage = null;
               psUpdateMessage.close();
               psUpdateMessage = null;
            }            
         }
         
         if (batch)
         {
            if (messageDeletionsInBatch)
            {
               int[] rows = psDeleteMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("DELETE_MESSAGE"), rows, "deleted");
               }
               
               psDeleteMessage.close();
               psDeleteMessage = null;
            }
            if (messageUpdatesInBatch)
            {
               int[] rows = psUpdateMessage.executeBatch();
               
               if (trace)
               {
                  logBatchUpdate(getSQLStatement("UPDATE_MESSAGE_CHANNELCOUNT"), rows, "updated");
               }
               
               psUpdateMessage.close();
               psUpdateMessage = null;
            }
         }
      }
      catch (Exception e)
      {
         wrap.exceptionOccurred();
         throw e;
      }
      finally
      {
         if (psDeleteMessage != null)
         {
            try
            {
               psDeleteMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (psUpdateMessage != null)
         {
            try
            {
               psUpdateMessage.close();
            }
            catch (Throwable t)
            {
            }
         }
         if (conn != null)
         {
            try
            {
               conn.close();
            }
            catch (Throwable e)
            {
            }
         }
         try
         {
            wrap.end();
         }
         finally
         {
            if (wrap.isFailed())
            {
               //reverse any removes
               this.incPersistentCounts(removesToReverse);
            }
            //release locks
            this.releaseLocks(refs);
         }
      }      
   }
   
   
   protected void addTXRecord(Connection conn, Transaction tx) throws Exception
   {
      if (trace)
      {
         log.trace("Inserting tx record for " + tx);
      }
      
      PreparedStatement ps = null;
      String statement = "UNDEFINED";
      int rows = -1;
      int formatID = -1;
      try
      {
         statement = getSQLStatement("INSERT_TRANSACTION");
         
         ps = conn.prepareStatement(statement);
         
         ps.setLong(1, tx.getId());
         
         Xid xid = tx.getXid();
         formatID = xid.getFormatId();
         ps.setBytes(2, xid.getBranchQualifier());
         ps.setInt(3, formatID);
         ps.setBytes(4, xid.getGlobalTransactionId());
         
         rows = ps.executeUpdate();
         
      }
      finally
      {
         if (trace)
         {
            String s = JDBCUtil.statementToString(statement, new Long(tx.getId()), "<byte-array>",
                  new Integer(formatID), "<byte-array>");
            log.trace(s + (rows == -1 ? " failed!" : " inserted " + rows + " row(s)"));
         }
         try
         {
            if (ps != null)
            {
               ps.close();
            }
         }
         catch (Throwable e)
         {
            //Ignore
         }
      }
   }
   
   protected void removeTXRecord(Connection conn, Transaction tx) throws SQLException
   {
      PreparedStatement ps = null;
      try
      {
         ps = conn.prepareStatement(getSQLStatement("DELETE_TRANSACTION"));
         
         ps.setLong(1, tx.getId());
         
         int rows = ps.executeUpdate();
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("DELETE_TRANSACTION"), new Long(tx.getId())) + " removed " + rows + " row(s)");
         }
      }
      finally
      {
         try
         {
            if (ps != null)
            {
               ps.close();
            }
         }
         catch (Throwable e)
         {
            //Ignore
         }
      }
   }  
   
   protected void addReference(long channelID, MessageReference ref, PreparedStatement ps) throws Exception
   {
      if (trace)
      {
         log.trace("adding " + ref + " to channel " + channelID);
      }
      
      ps.setLong(1, channelID);
      ps.setLong(2, ref.getMessageID());
      ps.setNull(3, Types.BIGINT);
      ps.setString(4, "C");
      if (ref.getPagingOrder() == -1)
      {
         ps.setNull(5, Types.BIGINT);
      }
      else
      {
         ps.setLong(5, ref.getPagingOrder());
      }
      ps.setInt(6, ref.getDeliveryCount());
      ps.setString(7, ref.isReliable() ? "Y" : "N");
   }
   
   protected void removeReference(long channelID, MessageReference ref, PreparedStatement ps) throws Exception
   {
      if (trace)
      {
         log.trace("removing " + ref + " from channel " + channelID);
      }
      
      ps.setLong(1, ref.getMessageID());
      ps.setLong(2, channelID);      
   }
   
   protected void prepareToAddReference(long channelID, MessageReference ref, Transaction tx, PreparedStatement ps)
     throws Exception
   {
      if (trace)
      {
         log.trace("adding " + ref + " to channel " + channelID
               + (tx == null ? " non-transactionally" : " on transaction: " + tx));
      }
      
      ps.setLong(1, channelID);
      ps.setLong(2, ref.getMessageID());
      ps.setLong(3, tx.getId());
      ps.setString(4, "+");
      ps.setNull(5, Types.BIGINT);      
      ps.setInt(6, ref.getDeliveryCount());
      ps.setString(7, ref.isReliable() ? "Y" : "N");
   }
   
   protected void prepareToRemoveReference(long channelID, MessageReference ref, Transaction tx, PreparedStatement ps)
      throws Exception
   {
      if (trace)
      {
         log.trace("removing " + ref + " from channel " + channelID
               + (tx == null ? " non-transactionally" : " on transaction: " + tx));
      }
      
      ps.setLong(1, tx.getId()); 
      ps.setLong(2, ref.getMessageID());
      ps.setLong(3, channelID);           
   }
   
   protected void commitPreparedTransaction(Transaction tx, Connection conn) throws Exception
   {
      PreparedStatement ps = null;
        
      try
      {
         ps = conn.prepareStatement(getSQLStatement("COMMIT_MESSAGE_REF1"));
         
         ps.setLong(1, tx.getId());        
         
         int rows = ps.executeUpdate();
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("COMMIT_MESSAGE_REF1"), new Long(tx.getId())) + " removed " + rows + " row(s)");
         }
         
         ps.close();
         ps = conn.prepareStatement(getSQLStatement("COMMIT_MESSAGE_REF2"));
         ps.setLong(1, tx.getId());         
         
         rows = ps.executeUpdate();
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("COMMIT_MESSAGE_REF2"), null, new Long(tx.getId())) + " updated " + rows
                  + " row(s)");
         }
         
         removeTXRecord(conn, tx);
      }
      finally
      {
         if (ps != null)
         {
            try
            {
               ps.close();
            }
            catch (Throwable e)
            {
            }
         }
      }
   }
   
   protected void rollbackPreparedTransaction(Transaction tx, Connection conn) throws Exception
   {
      PreparedStatement ps = null;
      
      try
      {
         ps = conn.prepareStatement(getSQLStatement("ROLLBACK_MESSAGE_REF1"));
         
         ps.setLong(1, tx.getId());         
         
         int rows = ps.executeUpdate();
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("ROLLBACK_MESSAGE_REF1"), new Long(tx.getId())) + " removed " + rows + " row(s)");
         }
         
         ps.close();
         
         ps = conn.prepareStatement(getSQLStatement("ROLLBACK_MESSAGE_REF2"));
         ps.setLong(1, tx.getId());
         
         rows = ps.executeUpdate();
         
         if (trace)
         {
            log.trace(JDBCUtil.statementToString(getSQLStatement("ROLLBACK_MESSAGE_REF2"), null, new Long(tx.getId())) + " updated " + rows
                  + " row(s)");
         }
         
         removeTXRecord(conn, tx);
      }
      finally
      {
         if (ps != null)
         {
            try
            {
               ps.close();
            }
            catch (Throwable e)
            {
            }
         }
      }
   }
   
   protected byte[] mapToBytes(Map map) throws Exception
   {
      if (map == null || map.isEmpty())
      {
         return null;
      }
      
      final int BUFFER_SIZE = 1024;
      
      JBossObjectOutputStream oos = null;
      
      try
      {
         ByteArrayOutputStream bos = new ByteArrayOutputStream(BUFFER_SIZE);
         
         oos = new JBossObjectOutputStream(bos);
         
         RoutableSupport.writeMap(oos, map, true);
         
         return bos.toByteArray();
      }
      finally
      {
         if (oos != null)
         {
            oos.close();
         }
      }
   }
   
   protected HashMap bytesToMap(byte[] bytes) throws Exception
   {
      if (bytes == null)
      {
         return new HashMap();
      }
      
      JBossObjectInputStream ois = null;
      
      try
      {
         ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
         
         ois = new JBossObjectInputStream(bis);
         
         Map m = RoutableSupport.readMap(ois, true);
         HashMap map;
         if (!(m instanceof HashMap))
         {
            map = new HashMap(m);
         }
         else
         {
            map = (HashMap) m;
         }
         
         return map;
      }
      finally
      {
         if (ois != null)
         {
            ois.close();
         }
      }
   }
   
   protected void updateMessageChannelCount(Message m, PreparedStatement ps) throws Exception
   {
      ps.setInt(1, m.getPersistentChannelCount());
      ps.setLong(2, m.getMessageID());
   }
   
   /**
    * Stores the message in the MESSAGE table.
    */
   protected void storeMessage(Message m, PreparedStatement ps) throws Exception
   {      
      // physically insert the row in the database
      // first set the fields from org.jboss.messaging.core.Routable
      ps.setLong(1, m.getMessageID());
      ps.setString(2, m.isReliable() ? "Y" : "N");
      ps.setLong(3, m.getExpiration());
      ps.setLong(4, m.getTimestamp());
      ps.setByte(5, m.getPriority());
      
      byte[] bytes = mapToBytes(((MessageSupport) m).getHeaders());
      if (bytes != null)
      {
         setBytes(ps, 6, bytes);
      }
      else
      {
         ps.setNull(6, Types.LONGVARBINARY);
      }
      
      // now set the fields from org.jboss.messaging.core.Message
      
      byte[] payload = m.getPayloadAsByteArray();
      if (payload != null)
      {
         setBytes(ps, 7, payload);
      }
      else
      {
         ps.setNull(7, Types.LONGVARBINARY);
      }
      
      //The number of channels that hold a reference to the message - initially always 1
      ps.setInt(8, 1);
      
      //Now set the fields from org.joss.jms.message.JBossMessage if appropriate
      
      //FIXME - We are mixing concerns here
      //The basic JDBCPersistencManager should *only* know about core messages - not 
      //JBossMessages - we should subclass JBDCPersistenceManager and the JBossMessage
      //specific code in a subclass
      if (m instanceof JBossMessage)
      {
         JBossMessage jbm = (JBossMessage) m;
         
         ps.setByte(9, jbm.getType());
         if (jbm.getJMSType() != null)
         {
            ps.setString(10, jbm.getJMSType());
         }
         else
         {
            ps.setNull(10, Types.VARCHAR);
         }
         if (jbm.getJMSCorrelationID() != null)
         {
            ps.setString(11, jbm.getJMSCorrelationID());
         }
         else
         {
            ps.setNull(11, Types.VARCHAR);
         }
         if (jbm.getJMSCorrelationIDAsBytes() != null)
         {
            ps.setBytes(12, jbm.getJMSCorrelationIDAsBytes());
         }
         else
         {
            ps.setNull(12, Types.BINARY);
         }
         
         JBossDestination jbd = (JBossDestination) jbm.getJMSDestination();
        
         ps.setString(13, (jbd.isQueue() ? "Q" : "T") + jbd);
         
         JBossDestination replyTo = (JBossDestination) jbm.getJMSReplyTo();
         if (replyTo == null)
         {
            ps.setNull(14, Types.BIGINT);
         }
         else
         {            
            ps.setString(14, (replyTo.isQueue() ? "Q" : "T") + replyTo);
         }
         
         bytes = mapToBytes(jbm.getJMSProperties());
         if (bytes != null)
         {
            setBytes(ps, 15, bytes);
         }
         else
         {
            ps.setNull(15, Types.LONGVARBINARY);
         }
      }
      else
      {
         ps.setByte(9, CoreMessage.TYPE);
         ps.setNull(10, Types.VARCHAR);
         ps.setNull(11, Types.VARCHAR);
         ps.setNull(12, Types.BINARY);
         ps.setNull(13, Types.BIGINT);
         ps.setNull(14, Types.BIGINT);
         ps.setNull(15, Types.LONGVARBINARY);
      }
   }
   
   /**
    * Removes the message from the MESSAGE table.
    */
   protected void removeMessage(Message message, PreparedStatement ps) throws Exception
   {
      // physically delete the row in the database
      ps.setLong(1, message.getMessageID());      
   }
     
   protected void setBytes(PreparedStatement ps, int columnIndex, byte[] bytes) throws Exception
   {
      if (usingBinaryStream)
      {
         //Set the bytes using a binary stream - likely to be better for large byte[]
         
         InputStream is = null;
         
         try
         {
            is = new ByteArrayInputStream(bytes);
            
            ps.setBinaryStream(columnIndex, is, bytes.length);
         }
         finally
         {
            if (is != null)
            {
               is.close();
            }
         }
      }
      else
      {
         //Set the bytes using setBytes() - likely to be better for smaller byte[]
         ps.setBytes(columnIndex, bytes);
      }
   }
   
   protected byte[] getBytes(ResultSet rs, int columnIndex) throws Exception
   {
      if (usingBinaryStream)
      {
         //Get the bytes using a binary stream - likely to be better for large byte[]
         
         InputStream is = null;
         ByteArrayOutputStream os = null;
         
         final int BUFFER_SIZE = 4096;
         
         try
         {
            InputStream i = rs.getBinaryStream(columnIndex);
            
            if (i == null)
            {
               return null;
            }
            
            is = new BufferedInputStream(rs.getBinaryStream(columnIndex), BUFFER_SIZE);
            
            os = new ByteArrayOutputStream(BUFFER_SIZE);
            
            int b;
            while ((b = is.read()) != -1)
            {
               os.write(b);
            }
            
            return os.toByteArray();
         }
         finally
         {
            if (is != null)
            {
               is.close();
            }
            if (os != null)
            {
               os.close();
            }
         }
      }
      else
      {
         //Get the bytes using getBytes() - better for smaller byte[]
         return rs.getBytes(columnIndex);
      }
   }
   
   protected void getLocks(List refs)
   {
      Iterator iter = refs.iterator();
      while (iter.hasNext())
      {
         MessageReference ref = (MessageReference)iter.next();
         Message m = ref.getMessage();
         LockMap.instance.obtainLock(m);        
      }
   }
   
   protected void releaseLocks(List refs)
   {
      Iterator iter = refs.iterator();
      while (iter.hasNext())
      {
         MessageReference ref = (MessageReference)iter.next();
         Message m = ref.getMessage();
         LockMap.instance.releaseLock(m);         
      }
   }
   
   protected void incPersistentCounts(List refs)
   {
      Iterator iter = refs.iterator();
      
      while (iter.hasNext())
      {
         Object obj = iter.next();
         MessageReference ref;
         if (obj instanceof MessageReference)
         {
            ref = (MessageReference)obj;
         }
         else
         {
            ref = ((ChannelRefPair)obj).ref;
         }
         ref.getMessage().incPersistentChannelCount();
      }
   }
   
   protected void decPersistentCounts(List refs)
   {
      Iterator iter = refs.iterator();
      
      while (iter.hasNext())
      {
         Object obj = iter.next();
         MessageReference ref;
         if (obj instanceof MessageReference)
         {
            ref = (MessageReference)obj;
         }
         else
         {
            ref = ((ChannelRefPair)obj).ref;
         }
         ref.getMessage().decPersistentChannelCount();
      }
      
   }
   
   protected void logBatchUpdate(String name, int[] rows, String action)
   {
      int count = 0;
      for (int i = 0; i < rows.length; i++)
      {
         count += rows[i];
      }
      log.trace("Batch update " + name + ", " + action + " total of " + count + " rows");
   }
   
   //PersistentServiceSupport overrides ----------------------------
   
   protected Map getDefaultDDLStatements()
   {
      Map map = new HashMap();
      //Message reference
      map.put("CREATE_MESSAGE_REFERENCE",
              "CREATE TABLE JMS_MESSAGE_REFERENCE (CHANNELID BIGINT, " +
              "MESSAGEID BIGINT, TRANSACTIONID BIGINT, STATE CHAR(1), ORD BIGINT, PAGE_ORD BIGINT, " +
              "DELIVERYCOUNT INTEGER, RELIABLE CHAR(1), PRIMARY KEY(CHANNELID, MESSAGEID))");
      map.put("CREATE_IDX_MESSAGE_REF_TX", "CREATE INDEX JMS_MESSAGE_REF_TX ON JMS_MESSAGE_REFERENCE (TRANSACTIONID)");
      map.put("CREATE_IDX_MESSAGE_REF_ORD", "CREATE INDEX JMS_MESSAGE_REF_ORD ON JMS_MESSAGE_REFERENCE (ORD)");
      map.put("CREATE_IDX_MESSAGE_REF_PAGE_ORD", "CREATE INDEX JMS_MESSAGE_REF__PAGE_ORD ON JMS_MESSAGE_REFERENCE (PAGE_ORD)");
      map.put("CREATE_IDX_MESSAGE_REF_MESSAGEID", "CREATE INDEX JMS_MESSAGE_REF_MESSAGEID ON JMS_MESSAGE_REFERENCE (MESSAGEID)");
      map.put("CREATE_IDX_MESSAGE_REF_RELIABLE", "CREATE INDEX JMS_MESSAGE_REF_RELIABLE ON JMS_MESSAGE_REFERENCE (RELIABLE)");
      //Message
      map.put("CREATE_MESSAGE",
              "CREATE TABLE JMS_MESSAGE (MESSAGEID BIGINT, RELIABLE CHAR(1), " +
              "EXPIRATION BIGINT, TIMESTAMP BIGINT, PRIORITY TINYINT, COREHEADERS LONGVARBINARY, " +
              "PAYLOAD LONGVARBINARY, CHANNELCOUNT INTEGER, TYPE TINYINT, JMSTYPE VARCHAR(255), CORRELATIONID VARCHAR(255), " +
              "CORRELATIONID_BYTES VARBINARY(254), DESTINATION VARCHAR(255), REPLYTO VARCHAR(255), " +
              "JMSPROPERTIES LONGVARBINARY, " +
              "PRIMARY KEY (MESSAGEID))"); 
      //Transaction
      map.put("CREATE_TRANSACTION",
              "CREATE TABLE JMS_TRANSACTION (" +
              "TRANSACTIONID BIGINT, BRANCH_QUAL VARBINARY(254), " +
              "FORMAT_ID INTEGER, GLOBAL_TXID VARBINARY(254), PRIMARY KEY (TRANSACTIONID))");
      //Counter
      map.put("CREATE_COUNTER",
              "CREATE TABLE JMS_COUNTER (NAME VARCHAR(255), NEXT_ID BIGINT, PRIMARY KEY(NAME))");
      //Sequences
      map.put("CREATE_ORDERING_SEQUENCE", "CREATE SEQUENCE REF_ORD");
      return map;
   }
      
   protected Map getDefaultDMLStatements()
   {                
      Map map = new HashMap();
      //Message reference
      map.put("INSERT_MESSAGE_REF",
              "INSERT INTO JMS_MESSAGE_REFERENCE (CHANNELID, MESSAGEID, TRANSACTIONID, STATE, ORD, PAGE_ORD, DELIVERYCOUNT, RELIABLE) " +
              "VALUES (?, ?, ?, ?, NEXT VALUE FOR REF_ORD, ?, ?, ?)");
      map.put("DELETE_MESSAGE_REF", "DELETE FROM JMS_MESSAGE_REFERENCE WHERE MESSAGEID=? AND CHANNELID=? AND STATE='C'");
      map.put("UPDATE_MESSAGE_REF",
              "UPDATE JMS_MESSAGE_REFERENCE SET TRANSACTIONID=?, STATE='-' " +
              "WHERE MESSAGEID=? AND CHANNELID=? AND STATE='C'");
      map.put("UPDATE_PAGE_ORDER", "UPDATE JMS_MESSAGE_REFERENCE SET PAGE_ORD = ? WHERE MESSAGEID=? AND CHANNELID=?");
      map.put("COMMIT_MESSAGE_REF1", "UPDATE JMS_MESSAGE_REFERENCE SET STATE='C', TRANSACTIONID = NULL WHERE TRANSACTIONID=? AND STATE='+'");
      map.put("COMMIT_MESSAGE_REF2", "DELETE FROM JMS_MESSAGE_REFERENCE WHERE TRANSACTIONID=? AND STATE='-'");
      map.put("ROLLBACK_MESSAGE_REF1", "DELETE FROM JMS_MESSAGE_REFERENCE WHERE TRANSACTIONID=? AND STATE='+'");
      map.put("ROLLBACK_MESSAGE_REF2", "UPDATE JMS_MESSAGE_REFERENCE SET STATE='C', TRANSACTIONID = NULL WHERE TRANSACTIONID=? AND STATE='-'");
      map.put("LOAD_PAGED_REFS",
              "SELECT MESSAGEID, DELIVERYCOUNT, PAGE_ORD, RELIABLE FROM JMS_MESSAGE_REFERENCE " +
              "WHERE CHANNELID = ? AND PAGE_ORD BETWEEN ? AND ? ORDER BY PAGE_ORD");
      map.put("LOAD_UNPAGED_REFS",
              "SELECT MESSAGEID, DELIVERYCOUNT, RELIABLE FROM JMS_MESSAGE_REFERENCE " +
              "WHERE PAGE_ORD IS NULL and CHANNELID = ? ORDER BY ORD");
      map.put("UPDATE_RELIABLE_REFS_NOT_PAGED", "UPDATE JMS_MESSAGE_REFERENCE SET PAGE_ORD = NULL WHERE PAGE_ORD BETWEEN ? AND ? AND CHANNELID=?");   
      map.put("DELETE_UNRELIABLE_REFS", "DELETE FROM JMS_MESSAGE_REFERENCE WHERE RELIABLE = 'N'");
      map.put("SHIFT_PAGE_ORDER", "UPDATE JMS_MESSAGE_REFERENCE SET PAGE_ORD = PAGE_ORD + ? WHERE CHANNELID = ?");
      map.put("SELECT_MIN_MAX_PAGE_ORD", "SELECT MIN(PAGE_ORD), MAX(PAGE_ORD) FROM JMS_MESSAGE_REFERENCE WHERE CHANNELID = ?");
      //Message
      map.put("LOAD_MESSAGES",
              "SELECT MESSAGEID, RELIABLE, EXPIRATION, TIMESTAMP, " +
              "PRIORITY, COREHEADERS, PAYLOAD, CHANNELCOUNT, TYPE, JMSTYPE, CORRELATIONID, " +
              "CORRELATIONID_BYTES, DESTINATION, REPLYTO, JMSPROPERTIES " +
              "FROM JMS_MESSAGE");
      map.put("INSERT_MESSAGE",
              "INSERT INTO JMS_MESSAGE (MESSAGEID, RELIABLE, EXPIRATION, " +
              "TIMESTAMP, PRIORITY, COREHEADERS, PAYLOAD, CHANNELCOUNT, TYPE, JMSTYPE, CORRELATIONID, " +
              "CORRELATIONID_BYTES, DESTINATION, REPLYTO, JMSPROPERTIES) " +
              "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)" );
      map.put("UPDATE_MESSAGE_CHANNELCOUNT", "UPDATE JMS_MESSAGE SET CHANNELCOUNT=? WHERE MESSAGEID=?");
      map.put("DELETE_MESSAGE", "DELETE FROM JMS_MESSAGE WHERE MESSAGEID=?");
      map.put("MESSAGEID_COLUMN", "MESSAGEID");
      map.put("UPDATE_UNRELIABLE_CHANNELCOUNT",
              "UPDATE JMS_MESSAGE M SET M.CHANNELCOUNT = M.CHANNELCOUNT - 1 WHERE " +
              "M.MESSAGEID IN (SELECT MR.MESSAGEID FROM JMS_MESSAGE_REFERENCE MR WHERE MR.RELIABLE = 'N' AND MR.CHANNELID = ?)");
      map.put("DELETE_UNREFFED_MESSAGES", "DELETE FROM JMS_MESSAGE WHERE CHANNELCOUNT = 0");
      //Transaction
      map.put("INSERT_TRANSACTION",
              "INSERT INTO JMS_TRANSACTION (TRANSACTIONID, BRANCH_QUAL, FORMAT_ID, GLOBAL_TXID) " +
              "VALUES(?, ?, ?, ?)");
      map.put("DELETE_TRANSACTION", "DELETE FROM JMS_TRANSACTION WHERE TRANSACTIONID = ?");
      map.put("SELECT_PREPARED_TRANSACTIONS", "SELECT TRANSACTIONID, BRANCH_QUAL, FORMAT_ID, GLOBAL_TXID FROM JMS_TRANSACTION");
      //Counter
      map.put("UPDATE_COUNTER", "UPDATE JMS_COUNTER SET NEXT_ID = ? WHERE NAME=?");
      map.put("SELECT_COUNTER", "SELECT NEXT_ID FROM JMS_COUNTER WHERE NAME=?");
      map.put("INSERT_COUNTER", "INSERT INTO JMS_COUNTER (NAME, NEXT_ID) VALUES (?, ?)");
      //Other
      map.put("SELECT_ALL_CHANNELS", "SELECT DISTINCT(CHANNELID) FROM JMS_MESSAGE_REFERENCE");
      return map;
   }
   
   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------
        
   private static class ChannelRefPair
   {
      private long channelId;
      
      private MessageReference ref;
      
      private ChannelRefPair(long channelId, MessageReference ref)
      {
         this.channelId = channelId;
         
         this.ref = ref;
      }
   }
   
   private class TransactionCallback implements TxCallback
   {
      private Transaction tx;
      
      private List refsToAdd;
      
      private List refsToRemove;
      
      private TransactionCallback(Transaction tx)
      {
         this.tx = tx;
         
         refsToAdd = new ArrayList();
         
         refsToRemove = new ArrayList();
      }
      
      private void addReferenceToAdd(long channelId, MessageReference ref)
      {
         refsToAdd.add(new ChannelRefPair(channelId, ref));
      }
      
      private void addReferenceToRemove(long channelId, MessageReference ref)
      {
         refsToRemove.add(new ChannelRefPair(channelId, ref));
      }
      
      public void afterCommit(boolean onePhase)
      {
         //NOOP
      }
      
      public void afterPrepare()
      {
         //NOOP
      }
      
      public void afterRollback(boolean onePhase)
      {
         //NOOP
      }
      
      public void beforeCommit(boolean onePhase) throws Exception
      {
         if (onePhase)
         {
            handleBeforeCommit1PC(refsToAdd, refsToRemove, tx);
         }
         else
         {
            handleBeforeCommit2PC(refsToRemove, tx);
         }
      }
      
      public void beforePrepare() throws Exception
      {
         handleBeforePrepare(refsToAdd, refsToRemove, tx);
      }
      
      public void beforeRollback(boolean onePhase) throws Exception
      {
         if (onePhase)
         {
            //NOOP - nothing in db
         }
         else
         {
            handleBeforeRollback(refsToAdd, tx);
         }
      }
   }
   
   static class MessageOrderComparator implements Comparator
   {
      static MessageOrderComparator instance = new MessageOrderComparator();
      
      public int compare(Object o1, Object o2)
      {        
         MessageReference ref1 = (MessageReference)o1;
         MessageReference ref2 = (MessageReference)o2;

         long id1 = ref1.getMessageID();         
         long id2 = ref2.getMessageID(); 
         
         return (id1 < id2 ? -1 : (id1 == id2 ? 0 : 1));
      }      
   }
   
}
