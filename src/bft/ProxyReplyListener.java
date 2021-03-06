/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.communication.client.ReplyReceiver;
import bftsmart.reconfiguration.ClientViewController;
import bftsmart.tom.core.messages.TOMMessage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.util.AbstractMap.SimpleEntry;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class ProxyReplyListener implements ReplyReceiver {

    private Map<Integer, Entry<Common.Block, Common.Metadata[]>[]> replies;
    private Map<Integer, Common.Block> responses;
    private ClientViewController viewManager;
    private Comparator<Entry<Common.Block, Common.Metadata[]>> comparator;
    private int replyQuorum;
    private int next;
            
    private Log logger;
    
    public ProxyReplyListener(ClientViewController viewManager) {
        
        logger = LogFactory.getLog(ProxyReplyListener.class);
        
        this.viewManager = viewManager;
        responses = new ConcurrentHashMap<>();
        replies = new HashMap<>();
        replyQuorum = getReplyQuorum();
        
        comparator = (Entry<Common.Block, Common.Metadata[]> o1, Entry<Common.Block, Common.Metadata[]> o2) -> o1.getKey().equals(o2.getKey()) && // compare entire block
                o1.getValue()[0].getValue().equals(o2.getValue()[0].getValue()) && // compare block signature value
                o1.getValue()[1].getValue().equals(o2.getValue()[1].getValue()) // compare config signature value
                ? 0 : -1 //TODO: compare the signature values too

        ;
    }
    
    @Override
    public synchronized void replyReceived(TOMMessage tomm) {            

        logger.debug("Replica " + tomm.getSender());
        logger.debug("Sequence " + tomm.getSequence());
        
        Common.Block response = null;
        
        if (tomm.getSequence() < next) { // ignore replies that no longer matter
            
            replies.remove(tomm.getSequence());
            responses.remove(tomm.getSequence());
            return;
        }
        int pos = viewManager.getCurrentViewPos(tomm.getSender());

        if (pos < 0) { //ignore messages that don't come from replicas
            return;
        }
                
        if (replies.get(tomm.getSequence()) == null) //avoid nullpointer exception
            replies.put(tomm.getSequence(), new Entry[viewManager.getCurrentViewN()]);
        
        byte[][] contents = null;
        Common.Block block = null;
        Common.Metadata metadata[] = new Common.Metadata[2];
        
        try {
            contents = deserializeContents(tomm.getContent());
            if (contents == null || contents.length < 3) return;
            block = Common.Block.parseFrom(contents[0]);
            if (block == null) return;
            metadata[0] = Common.Metadata.parseFrom(contents[1]);
            if (metadata[0] == null) return;
            metadata[1] = Common.Metadata.parseFrom(contents[2]);
            if (metadata[1] == null) return;
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }
        
        Entry[] reps = replies.get(tomm.getSequence());
        
        reps[pos] = new SimpleEntry<>(block,metadata);

        int sameContent = 1;
      
        for (int i = 0; i < reps.length; i++) {
            
            if ((i != pos || viewManager.getCurrentViewN() == 1) && reps[i] != null
					&& (comparator.compare(reps[i], reps[pos]) == 0)) {
                                        
                sameContent++;
                if (sameContent >= replyQuorum) {
                    response = getBlock(reps, pos);
                    responses.put(tomm.getSequence(), response);
                }
            }
        }
        
        if (responses.get(next) != null) notifyAll();

    }

    public synchronized Common.Block getNext() {
        
        Common.Block ret = null;
        while ((ret = responses.get(next)) == null) {
            try {
                wait();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
            
        }
        next++;
        
        return ret;
    }
            
    private byte[] getSerializedBlock(byte[] contents) {

        try {
            byte[] block = null;

            ByteArrayInputStream bis = new ByteArrayInputStream(contents);
            ObjectInput in = new ObjectInputStream(bis);
            int nContents = in.readInt();
            if (nContents < 1) {
                block = new byte[0];
            } else {
                int length = in.readInt();
                block = new byte[length];
                in.read(block);
            }

            in.close();
            bis.close();

            return block;
        } catch (IOException ex) {
            ex.printStackTrace();
            return new byte[0];
        }

    }
                           
    private Common.Block getBlock(Entry<Common.Block, Common.Metadata[]>[] replies, int lastReceived) {
        
            Common.Block.Builder block = replies[lastReceived].getKey().toBuilder();
            Common.BlockMetadata.Builder blockMetadata = block.getMetadata().toBuilder();
            
            Common.Metadata[] blockSigs = new Common.Metadata[replies.length];
            Common.Metadata[] configSigs = new Common.Metadata[replies.length];
            
            Common.Metadata.Builder allBlockSig = Common.Metadata.newBuilder();
            Common.Metadata.Builder allConfigSig = Common.Metadata.newBuilder();
            
            for (int i = 0; i < replies.length; i++) {
                
                if (replies[i] != null) {

                    blockSigs[i] = replies[i].getValue()[0];
                    configSigs[i] = replies[i].getValue()[1];
                    
                }
            }
            
            allBlockSig.setValue(blockSigs[lastReceived].getValue());
            
            for (Common.Metadata sig : blockSigs) {
                
                if (sig != null) {
                    
                    allBlockSig.addSignatures(sig.getSignatures(0));
                }
                
            }
            
            allConfigSig.setValue(configSigs[lastReceived].getValue());
            
            for (Common.Metadata sig : configSigs) {
                
                if (sig != null) {
                    
                    allConfigSig.addSignatures(sig.getSignatures(0));
                }

            }
            blockMetadata.setMetadata(Common.BlockMetadataIndex.SIGNATURES_VALUE, allBlockSig.build().toByteString());
            blockMetadata.setMetadata(Common.BlockMetadataIndex.LAST_CONFIG_VALUE, allConfigSig.build().toByteString());
            
            block.setMetadata(blockMetadata.build());
            
            return block.build();
            
    }
    
    static private byte[][] deserializeContents(byte[] bytes) throws IOException {
        
        byte[][] batch = null;
        
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream in = new DataInputStream(bis);
        int nContents =  in.readInt();
        batch = new byte[nContents][];
        
        for (int i = 0; i < nContents; i++) {
            
            int length = in.readInt();

            batch[i] = new byte[length];
            in.read(batch[i]);
        }
        in.close();
        bis.close();
 
        
        return batch;
    }
        
    private int receivedReplies(TOMMessage[] replies) {
        
        int count = 0;
        
        for (int i = 0; i < replies.length; i++)
            if (replies[i] != null) count++;
            
        return count;
    }
    
    private int getReplyQuorum() {
        
        if (viewManager.getStaticConf().isBFT()) {
                return (int) Math.ceil((viewManager.getCurrentViewN()
                                + viewManager.getCurrentViewF()) / 2) + 1;
        } else {
                return (int) Math.ceil((viewManager.getCurrentViewN()) / 2) + 1;
        }
    }
}
