package securechat;


import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import cryptoutils.communication.Request;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import cryptoutils.cipherutils.CryptoManager;
import java.net.InetAddress;
import java.security.cert.X509CRL;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import javafx.collections.ObservableList;
import securechat.model.Message;


public class Server extends HandshakeProtocol implements Runnable{ //Represents Alice in the protocol specifics
    private final int port;
    private Request req;
    private Request myReq;
    private final BlockingQueue<String> sendBuffer;
    private final ObservableList<Message> messageList;
    private int clientPort;
    private Receiver receiverRunnable = null;
    private Thread senderThread = null;
    private ServerSocket ssRef = null;
    
    public Server(int port, PrivateKey myKey,String issuer, Certificate myCertificate,Certificate CACertificate, ObservableList<Message> messageList, BlockingQueue<String> sendBuffer,X509CRL crl){
        super(myKey,issuer, myCertificate, CACertificate,crl);
        this.port = port;
        System.out.println(messageList==null);
        this.messageList = messageList;
        this.sendBuffer = sendBuffer;
    }
    
    public void stopProtocolServer() {
        try {
            ssRef.close();
        } catch(Exception e) {}
    }

    @Override
    public void run(){
        while(true){
            SharedState.getInstance().setConnected(false);            
            System.out.println("SERVER ISTANTIATED @ PORT: "+port);
            String requestIpAddress = null;
            try(
                ServerSocket ss = new ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"));
                Socket s = ss.accept();
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                ObjectInputStream oin = new ObjectInputStream(in);
                ObjectOutputStream oout = new ObjectOutputStream(out);
            ){
                ssRef = ss;
                System.out.println("SERVER WAITING FOR REQUEST");
                String requestHeader = (String)oin.readObject();          
                System.out.println("RECEIVED REQUEST");
                try {
                    SharedState.getInstance().setPendingRequest(true);
                    messageList.add(new Message(requestHeader, new Date(), "Do you want to connect?",1));
                    System.out.println("WAITING FOR RESPONSE...");
                    boolean res = SharedState.getInstance().waitForResponse();
                    System.out.println("RESPONSE: "+res);                    
                    if(!res){
                        System.out.println("TIMER EXPIRED OR ANSWER NOT Y, SETTING PROTOCOL DONE TO FALSE--server");
                        SharedState.getInstance().protocolDone(res);
                        continue;
                    }
                }catch(Exception e) {
                    e.printStackTrace();
                    continue;
                }
                oout.writeObject(myCertificate);
                if(!getRequest(oin)){
                    System.err.println("REQUEST CORRUPTED OR NOT AUTHENTIC---server");
                    SharedState.getInstance().protocolDone(false);
                    continue;
                }
                myReq = generateRequest();
                oout.writeObject(myReq.getEncrypted(req.getPublicKey()));
                Object[] receivedChallenge = receiveChallenge(oin);
                if(!(boolean)receivedChallenge[0]){
                    System.err.println("CHALLENGE NOT FULFILLED BY THE OTHER USER---server");
                    throw new Exception("Challenge not fulfilled by the other user");
                }
                clientPort = (int)receivedChallenge[1];
                sendChallenge(oout,req.getTimestamp().toEpochMilli(),-1);
                success = true;
                requestIpAddress = s.getInetAddress().getHostAddress();
            }catch(Exception e){
                System.out.println("IN EXCEPTION SETTING PROTOCOLD DONE TO FALSE--server");
                SharedState.getInstance().protocolDone(false);
                e.printStackTrace();
                if(Thread.interrupted()) return;
                continue;
            }
            
            if(!success) continue;
            SharedState.getInstance().setConnected(true);
            System.out.println("PROTOCOL ENDED CORRECTLY WITH: "+requestIpAddress+":"+clientPort+" ----server");
            System.out.println("CREATING MESSAGING THREAD WITH: "+requestIpAddress+":"+clientPort+1+" USERNAME: "+req.getIssuer()+"---server");
            receiverRunnable = new Receiver(messageList, req.getIssuer(), authKey, symKey, port+1, requestIpAddress);
            Runnable senderRunnable = new Sender(sendBuffer, authKey, symKey, clientPort+1, requestIpAddress);
            Thread receiverThread = new Thread(receiverRunnable);
            this.senderThread = new Thread(senderRunnable);
            sendBuffer.clear();
            System.out.println("QUEUE CLEARED---server");
            senderThread.start();
            receiverThread.start();
            System.out.println("SETTING PROTOCOL DONE TO TRUE--server");
            SharedState.getInstance().protocolDone(success);
            System.out.println("BEFORE JOIN---Server");
            try {             
                receiverThread.join();
                System.out.println("RECEIVER JOIN DONE---Server");
                if(senderThread.isAlive()){
                    senderThread.interrupt();
                    System.out.println("ADDING INT TO THE BLOCKINGQUEUE--server");
                    sendBuffer.add("int");
                    senderThread.join();
                }else
                    System.out.println("STOP, IT IS ALREADY DEAD---server");
                System.out.println("SENDER JOIN DONE---Server");
            } catch(Exception e) {}
        }
    }
    /**
     * Gets the Client request, sets the symmetric key with the received value and verifies if the Certificate in the request is revoked or not 
     * @param obj
     * @return
     * @throws IOException
     * @throws ClassNotFoundException
     * @throws NoSuchAlgorithmException
     * @throws NoSuchPaddingException
     * @throws InvalidKeyException
     * @throws IllegalBlockSizeException
     * @throws BadPaddingException
     * @throws CertificateException 
     */
    private boolean getRequest(ObjectInputStream obj) throws IOException, ClassNotFoundException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, CertificateException{
        this.req = Request.fromEncryptedRequest((byte []) obj.readObject(),myKey); //first we read the length we expect LBA||nb||S(sb,LBA||nb)
        this.symKey = req.getSecretKey();
        System.out.println((crl == null));
        if(crl != null)
            System.out.println((crl.isRevoked(req.getCertificate())));        
        return (req.verify(CACertificate, null) && (crl == null || !crl.isRevoked(req.getCertificate()))); 
    }
    /**
     * Generates the authentication key, adds the timestamp and signs the request with his private key
     * @return
     * @throws CertificateEncodingException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws SignatureException 
     */
    private Request generateRequest() throws CertificateEncodingException, NoSuchAlgorithmException, InvalidKeyException, SignatureException{
        this.authKey = CryptoManager.generateAES256RandomSecretKey();
        Request req = new Request(issuer,this.req.getIssuer(),myCertificate,authKey);
        this.myNonce =req.getTimestamp().toEpochMilli();
        req.sign(myKey);
        return req;
    }      
    public Thread getSender(){
        return senderThread;
    }
    public Receiver getReceiver(){
        return receiverRunnable;
    }
}
