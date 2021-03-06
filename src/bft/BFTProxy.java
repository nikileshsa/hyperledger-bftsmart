/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.core.messages.TOMMessageType;
import com.etsy.net.JUDS;
import com.etsy.net.UnixDomainSocket;
import com.etsy.net.UnixDomainSocketServer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class BFTProxy {

    /**
     * @param args the command line arguments
     */
    private static UnixDomainSocketServer recvServer = null;
    private static ServerSocket sendServer = null;
    private static DataInputStream is;
    private static DataOutputStream os;
    private static UnixDomainSocket recvSocket = null;
    private static Socket sendSocket = null;
    private static ReceiverThread[] recvPool = null;
    private static ExecutorService executor = null;

    private static long PreferredMaxBytes = 0;
    private static long MaxMessageCount = 0;
    private static long BatchTimeout = 0;
    private static int poolSize = 0;
    private static int initID;

    private static AsynchServiceProxy proxy;
    private static ProxyReplyListener listener;
    private static Timer timer = new Timer();
        
    private static Log logger;
    
    //measurements
    private static int interval = 10000;
    private static long envelopeMeasurementStartTime = -1;
    private static long blockMeasurementStartTime = -1;
    private static long sigsMeasurementStartTime = -1;
    private static int countEnvelopes = 0;
    private static int countBlocks = 0;
    private static int countSigs = 0;

    public static void main(String args[]) {

        if(args.length < 3) {
            System.out.println("Use: java BFTNode <proxy id> <recv port> <send port>");
            System.exit(-1);
        }    
        
        BFTProxy.logger = LogFactory.getLog(BFTProxy.class);
        initID = Integer.parseInt(args[0]);
        
        proxy = new AsynchServiceProxy(initID, BFTNode.BFTSMART_CONFIG_FOLDER);
        listener = new ProxyReplyListener(proxy.getViewManager());
        
        int recvPort = Integer.parseInt(args[1]);
        int sendPort = Integer.parseInt(args[2]);
        
        proxy.getCommunicationSystem().setReplyReceiver(listener);

        try {
            recvServer = new  UnixDomainSocketServer("/tmp/bft.sock", JUDS.SOCK_STREAM, 10);
            sendServer = new ServerSocket(sendPort);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {

            logger.info("Waiting for local connections...");
            
            recvSocket = recvServer.accept();
            sendSocket = sendServer.accept();
            is = new DataInputStream(recvSocket.getInputStream());
            os = new DataOutputStream(sendSocket.getOutputStream());

            new SenderThread().start();

            poolSize = (int) readInt();

            logger.info("Read pool size: " + poolSize);
            //recvPool = new ReceiverThread[poolSize];
                        
            PreferredMaxBytes = readInt();

            logger.info("Read PreferredMaxBytes: " + PreferredMaxBytes);

            MaxMessageCount = readInt();

            logger.info("Read MaxMessageCount: " + MaxMessageCount);
            
            BatchTimeout = readLong(is);

            logger.info("Read BatchTimeout: " + BatchTimeout);
            
            byte[] bytes = readBytes(is);
            
            logger.info("Read Genesis block");

            proxy.invokeAsynchRequest(serializeBatchParams(), null, TOMMessageType.ORDERED_REQUEST);
            proxy.invokeAsynchRequest(bytes, null, TOMMessageType.ORDERED_REQUEST);
            
            timer.schedule(new BatchTimeout(), (BatchTimeout / 1000000));
            
            executor = Executors.newFixedThreadPool(poolSize);

            for (int i = 0; i < poolSize; i++) {
                
                UnixDomainSocket socket = recvServer.accept();
                
                //socket.setTcpNoDelay(true);
                //socket.setReceiveBufferSize(64 * 1024);
                
                executor.execute(new ReceiverThread(socket, i + initID + 1));
                
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static byte[] readBytes(DataInputStream is) throws IOException {

        long size = readLong(is);

        logger.debug("Read number of bytes: " + size);

        byte[] bytes = new byte[(int) size];

        is.read(bytes);

        logger.debug("Read all bytes!");

        return bytes;

    }
    
    private static long readLong(DataInputStream is) throws IOException {
        byte[] buffer = new byte[8];

        is.read(buffer);

        //This is for little endian
        //long value = 0;
        //for (int i = 0; i < by.length; i++)
        //{
        //   value += ((long) by[i] & 0xffL) << (8 * i);
        //}
        //This is for big endian
        long value = 0;
        for (int i = 0; i < buffer.length; i++) {
            value = (value << 8) + (buffer[i] & 0xff);
        }

        return value;
    }

    private static long readInt() throws IOException {

        byte[] buffer = new byte[4];
        long value = 0;

        is.read(buffer);

        for (int i = 0; i < buffer.length; i++) {
            value = (value << 8) + (buffer[i] & 0xff);
        }

        return value;

    }
    
    static private byte[] serializeBatchParams() throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = new ObjectOutputStream(bos);
        out.writeLong(PreferredMaxBytes);
        out.writeLong(MaxMessageCount);
        out.flush();
        bos.flush();
        out.close();
        bos.close();
        return bos.toByteArray();
    }
            
    private static synchronized void resetTimer() {
        
        if (timer != null) timer.cancel();
        timer = new Timer();
        timer.schedule(new BatchTimeout(), (BatchTimeout / 1000000));
    }
    
    private static class ReceiverThread extends Thread {
        
        private int id;
        private UnixDomainSocket recv;
        private DataInputStream input;
        private AsynchServiceProxy out;
        
        public ReceiverThread(UnixDomainSocket recv, int id) throws IOException {
                        
            this.id = id;
            this.recv = recv;
            this.input = new DataInputStream(this.recv.getInputStream());
            this.out = new AsynchServiceProxy(this.id, BFTNode.BFTSMART_CONFIG_FOLDER);
            
        }
        
        public void run() {
            
            byte[] bytes; 
            while (true) {
                

                try {
                    bytes = readBytes(this.input);
                } catch (IOException ex) {
                    Logger.getLogger(BFTProxy.class.getName()).log(Level.SEVERE, null, ex);
                    continue;
                }
                
                //logger.debug("Received envelope at connection #" + this.id);
                
                resetTimer();

                //CommonProtos.Envelope env = CommonProtos.Envelope.parseFrom(bytes);
                //logger.debug("Envelope Payload" + Arrays.toString(env.getPayload().toByteArray()));

                this.out.invokeAsynchRequest(bytes, null, TOMMessageType.ORDERED_REQUEST);

                if (envelopeMeasurementStartTime == -1) {
                    envelopeMeasurementStartTime = System.currentTimeMillis();
                }

                countEnvelopes++;

                if (countEnvelopes % interval == 0) {

                    float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - envelopeMeasurementStartTime));
                    logger.info("Throughput = " + tp + " envelopes/sec");
                    envelopeMeasurementStartTime = System.currentTimeMillis();

                }
        
                //byte[] reply = proxy.invokeOrdered(bytes);

                //by = new byte[8];
                //by[0] = (byte) ((byte) value>>56);
                //by[1] = (byte) ((byte) value>>48);
                //by[2] = (byte) ((byte) value>>40);
                //by[3] = (byte) ((byte) value>>32);
                //by[4] = (byte) ((byte) value>>24);
                //by[5] = (byte) ((byte) value>>16);
                //by[6] = (byte) ((byte) value>>8);
                //by[7] = (byte) ((byte) value>>0);

            }
        }
    }
    private static class SenderThread extends Thread {

        public void run() {

            while (true) {

                Common.Block block = listener.getNext();

                //resetTimer();
                            
                if (block != null) {

                    try {
                        
                        byte[] bytes = block.toByteArray();
                        os.writeLong(bytes.length);
                        os.write(bytes);
                        
                        
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

    }
    
    private static class BatchTimeout extends TimerTask {

        @Override
        public void run() {
            
            proxy.invokeAsynchRequest(new byte[0], null, TOMMessageType.ORDERED_REQUEST);
            
            timer = new Timer();
            timer.schedule(new BatchTimeout(), (BatchTimeout / 1000000));

        }
    
    }
}
