package securechat;

import cryptoutils.cipherutils.CertificateManager;
import cryptoutils.cipherutils.CryptoManager;
import cryptoutils.communication.Request;
import java.io.FileInputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.*;
import javafx.application.Application;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Alert.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import securechat.frontend.MessageEntry;
import securechat.model.Message;

public class SecureChat extends Application {
    private final Label connectToLabel = new Label("NAME AND HOST TO CONNECT WITH");
    private final TextField connectToField = new TextField();
    private final Button connectButton = new Button("CONNECT");
    private final static ArrayList<Message> arrayList = new ArrayList<>();
    private final static ObservableList<Message> myL = FXCollections.observableArrayList(arrayList);
    private final ListView<Message> l = new ListView<>(myL);
    private static BlockingQueue<String> sendBuffer = new LinkedBlockingQueue<>();
    private final TextArea messageArea = new TextArea();
    private final Button sendButton = new Button("SEND");
    private final Properties properties = new Properties();
    private Stage appStage;
    private PrivateKey pk;
    private Certificate myCertificate;
    private String myUsername;
    private Certificate authorityCertificate;
    private int listeningPort;
    
    private HBox buildConnectControls() {
        HBox connectControls = new HBox(5);
        connectControls.getChildren().addAll(connectToField,connectButton);
        HBox.setHgrow(connectButton,Priority.ALWAYS);
        HBox.setHgrow(connectToField,Priority.ALWAYS);  
        connectButton.setOnAction(ev -> {handleConnect();});
        return connectControls;
    }
    
    private void handleConnect() {
        String query = connectToField.getText();
        String queryElements[] = query.split("@");
        if(queryElements.length < 2) return;
        String username = queryElements[0];
        String hostNamePort = queryElements[1];
        String hostNameElements[] = hostNamePort.split(":");
        if(hostNameElements.length < 2) return;
        String hostName = hostNameElements[0];
        String port = hostNameElements[1];
        System.out.println("Starting handshake protocol with: "+hostName+":"+port);
        Client connectThreadRunnable = new Client(hostName, Integer.parseInt(port),listeningPort, pk, myUsername, myCertificate, authorityCertificate,username);
        Thread connectThread = new Thread(connectThreadRunnable);
        connectThread.start();
        try {
            connectThread.join(3000);
            if(!connectThreadRunnable.getHandshakeResult()) {
                connectThread.interrupt();
                (new Alert(AlertType.ERROR,"Could not connect")).showAndWait().filter(res -> res == ButtonType.OK);
                return;
            };
            byte[] macKey = connectThreadRunnable.getAuthKey();
            byte[] symKey = connectThreadRunnable.getSymKey();
            Receiver messageReceiverRunnable = new Receiver(arrayList, username, macKey, symKey, listeningPort+1, hostName);
            Sender messageSenderRunnable = new Sender(sendBuffer, macKey, symKey, Integer.parseInt(port)+1, hostName);
            Thread receiverThread = new Thread(messageReceiverRunnable);
            Thread senderThread = new Thread(messageSenderRunnable);
            receiverThread.start();
            senderThread.start();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    private void configureTextFields() {
        connectToField.setId("usernameHostInput");
        connectToField.setPromptText("username@host"); 
        messageArea.setPromptText("Write your message here...");
        connectToField.setOnKeyTyped((KeyEvent event) -> {
            if(connectToField.getText().isEmpty()) connectButton.setDisable(true);
            else connectButton.setDisable(false);
        });
        messageArea.setOnKeyTyped((KeyEvent event) -> {
            if(messageArea.getText().isEmpty()) sendButton.setDisable(true);
            else sendButton.setDisable(false);            
        });
        messageArea.setOnKeyPressed((KeyEvent keyEvent) -> {
            KeyCombination submit = new KeyCodeCombination(KeyCode.ENTER, KeyCodeCombination.SHIFT_DOWN);
            if (submit.match(keyEvent))  {
                String text = messageArea.getText();
                if(text.isEmpty()) return;
                /*
                ////HERE WE ACTUALLY SEND THE MESSAGE OVER THE SECURE CHANNEL
                */
                Message m = new Message(myUsername,new Date(),text);
                myL.add(m);
                sendBuffer.add(m.getContent());
                messageArea.clear();         
                sendButton.setDisable(true);
            }
        });
    }
    
    private void setOnSendButtonClickHandler() {
        sendButton.setDisable(true);
        sendButton.setOnAction(ev -> {
            String text = messageArea.getText();
            Message m = new Message(myUsername,new Date(),text);
            myL.add(m);
            sendBuffer.add(m.getContent());
            messageArea.clear();            
            sendButton.setDisable(true);
        });
    }

    private void setOnConnectButtonClickHandler() {
        connectButton.setDisable(true);
    }    
    
    
    
    private GridPane buildSceneGrid(HBox connectControls) {
        GridPane root = new GridPane();
        root.setPadding(new Insets(10,10,10,10));
        root.setHgap(5);
        root.setVgap(10);
        root.setAlignment(Pos.TOP_CENTER);
        root.add(connectToLabel,0,0,2,1);
        root.add(connectControls,0,1,2,1);
        root.add(l,0,2,2,1);
        root.add(messageArea,0,3,2,1);
        root.add(sendButton,0,4,2,1);
        GridPane.setHalignment(connectToLabel, HPos.CENTER);
        GridPane.setHalignment(connectControls,HPos.CENTER);
        GridPane.setHalignment(messageArea, HPos.CENTER);
        sendButton.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHalignment(sendButton, HPos.CENTER);   
        GridPane.setFillWidth(sendButton, Boolean.TRUE);
        return root;
    }
    
    private void configureListView() {
        l.setCellFactory((ListView<Message> p) -> {
            MessageEntry cell = new MessageEntry();
            return cell;
        });
    }
    
    @Override
    public void start(Stage primaryStage) {
        configureTextFields();
        setOnConnectButtonClickHandler();
        setOnSendButtonClickHandler();
        HBox connectControls = buildConnectControls();
        configureListView();
        GridPane root = buildSceneGrid(connectControls);
       
        Scene scene = new Scene(root, 320, 600);
        primaryStage.setTitle("SecureChat - Disconnected");
        primaryStage.setResizable(false);
        primaryStage.setScene(scene);
        primaryStage.show();
        root.requestFocus();
        l.scrollTo(myL.size()-1);
        appStage = primaryStage;
        loadCryptoSpecs();
        /* HERE SOMETHING TO START THREADS AND THINGS */
        System.out.println("Protocol listener started...");
        Server protocolServerRunnable = new Server(listeningPort,pk,myUsername,myCertificate,authorityCertificate,arrayList,sendBuffer);
        Thread protocolServerThread = new Thread(protocolServerRunnable);
        protocolServerThread.start();
    }
    
    public static Object[] askRequestConfirmation(Request request) {
        Alert confAlert = new Alert(AlertType.CONFIRMATION);
        confAlert.setTitle("SecureChat");
        confAlert.setHeaderText("Incoming request");
        confAlert.setContentText("Request from"+request.getIssuer()+"\nCertificate released to: "+request.getCertificateSubject()+"\nCertificate released by: "+request.getCertificateIssuer()+"\nAccept?");
        Object[] returns = new Object[3];
        returns[0] = (confAlert.showAndWait().get() == ButtonType.OK);
    //    returns[1] = myL;    // pass through constructor?
    //    returns[2] = sendBuffer; //idem?
        return returns;
    }
    
    private void loadCryptoSpecs() {
        try(FileInputStream fis = new FileInputStream("config.properties");) {
            properties.load(fis);
            myCertificate = CertificateManager.readCertFromFile(properties.getProperty("myCertPath"));
            if(myCertificate == null) throw new Exception("Certificate not found");
            Certificate authCert = CertificateManager.readCertFromFile(properties.getProperty("authcertpath"));
            if(!CertificateManager.verifyCertificate((X509Certificate)myCertificate, authCert)) throw new Exception("Invalid certificate");
            PrivateKey myPrivKey = CryptoManager.readRSAPrivateKeyFromPEMFile(properties.getProperty("privatekeypath"));
            PublicKey myPublicKey = myCertificate.getPublicKey(); 
            byte[] testBytes = {0x8,0x6};
            CryptoManager.decryptRSA(CryptoManager.encryptRSA(testBytes, myPublicKey), myPrivKey); //Should raise an exception and terminate the program if wrong
            
            Alert success = new Alert(AlertType.INFORMATION);
            success.setTitle("SecureChat");
            success.setHeaderText("Application started successfully");
            success.setContentText("1)Private key loaded\n2)Authority certificate loaded\n3)Identity online");
            success.showAndWait().filter(response -> response == ButtonType.OK);
            
            pk = myPrivKey;
            authorityCertificate = authCert;
            myUsername = properties.getProperty("myname");
            appStage.setTitle(myUsername + " - Online (Idle)");
            listeningPort = Integer.parseInt(properties.getProperty("defaultProtocolPort"));
        } catch(Exception e) {
            Alert error = new Alert(AlertType.ERROR);
            error.setTitle("SecureChat");
            error.setHeaderText("Application cannot be loaded correctly");
            error.setContentText("Message: "+e.getMessage());
            error.showAndWait().filter(response -> response == ButtonType.OK);
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
    
}
