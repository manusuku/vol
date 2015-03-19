/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package volicon.rpmservicevisits;

import java.net.HttpURLConnection;
import java.net.URL;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

import java.text.SimpleDateFormat;
import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.Properties;
import java.util.HashMap;
import java.util.Date;
import javax.xml.stream.XMLStreamException;

//import java.util.logging.*;
import org.apache.log4j.*;

public class RPMServiceVisits {
    
    private static final String SOAP_ACTION = "urn:rpm_service_visits#queryVisits";
    private static final String METHOD_NAME = "queryVisits";
    private static final String NAMESPACE = "urn:rpm_service_visits";

    // private static final String SERVICE_URL = "https://slbxasvasvr2.vzbi.com/services/service_visits.php";
    //private static final String SERVICE_URL = "http://rpm.better-than.tv/services/service_visits.php";

    private static String config_file = "";
    private static String service_url = "https://slbxasvasvr2.vzbi.com/services/service_visits.php";   
    private static String out_loc = "";
    private static String log_config = "";
    private static String work_loc = "";

    private static long    pull_dur = 300;
    private static long    pull_delay = 1800;
    private static long    sleep_dur = 0;

    //++static Level logLevel = Level.FINE;
    static Logger log  = Logger.getLogger("volicon.rpmservicevisits");

    /**
     * @param args the command line arguments
     */

    public static void main(String[] args) throws Exception 
       {

        setupEnv(args);

        if (log_config.isEmpty())
          {
            System.err.println("log_config for log4j not defined");
            System.exit(1);
          }

        try
         {
          PropertyConfigurator.configure(log_config);
         }
        catch(Exception e)
         {
          log.error("Error in loading config:" + log_config + " : Stack Details - " + e2str(e));
          System.exit(1);
         }

        // To disable Cert validation on https. HOST uses a self signed cert.

        // ++ Create a trust manager that does not validate certificate chains
	TrustManager[] trustAllCerts = new TrustManager[] {new X509TrustManager() 
           {
		public java.security.cert.X509Certificate[] getAcceptedIssuers() {
			return null;
		}
		public void checkClientTrusted(X509Certificate[] certs, String authType) {
		}
		public void checkServerTrusted(X509Certificate[] certs, String authType) {
		}
	   } };

	SSLContext sc = SSLContext.getInstance("SSL");
	sc.init(null, trustAllCerts, new java.security.SecureRandom());
	HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

	// Create all-trusting host name verifier
	HostnameVerifier allHostsValid = new HostnameVerifier() 
            {
		public boolean verify(String hostname, SSLSession session) {
		return true;
	        }
	    };
  
        // Install the all-trusting host verifier
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

        // Restart logic

        MappedByteBuffer restartDt = getRestartDt(); 

        long start = restartDt.getLong(0);
        long end = start + pull_dur;

        while(true)
         {
          if ( 0 == start )
            {
             start = System.currentTimeMillis()/1000 - (pull_delay + pull_dur);
             end = start + pull_dur;
             restartDt.putLong(0,start);
            }

          Date startDate = new Date(start * 1000);
          Date endDate = new Date(end * 1000);

          SimpleDateFormat dtFrmt = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

          log.info("Next Pull Period - "   
		+ "From:" + start + "-(" + dtFrmt.format(startDate) + ")"
                + ",To:" + end  + "-(" + dtFrmt.format(endDate) + ")" );
 
          String body = getRequestMessage(start, end);
        
          log.debug("Initiating Request : " + body );

          try
           {
            URL url = new URL(service_url);        
            HttpURLConnection rc = (HttpURLConnection)url.openConnection();
            rc.setRequestMethod("POST");
            rc.setDoOutput( true );  
            rc.setDoInput( true );   
            rc.setRequestProperty( "Content-Type", "text/xml; charset=utf-8" );        
            rc.setRequestProperty( "Content-Length", Integer.toString( body.length() ) );        
            rc.setRequestProperty("SOAPAction", SOAP_ACTION);
            rc.connect();
            OutputStreamWriter out = new OutputStreamWriter( rc.getOutputStream() );
            out.write(body);
            out.flush();        
        
            log.info("Request sent, reading response");
        
            InputStreamReader reader = new InputStreamReader( rc.getInputStream() );       

            if ( readResponseMessage(reader, start, end ) )
             {
                log.info("Completed extraction of Volicom Data");
                
                start = end; // + 1; // next start
                end = start + pull_dur;
                 
                // ++ Save the next Start time @ persistent storage.
                restartDt.putLong(0,start);

                if ( System.currentTimeMillis()/1000 < end + pull_delay )
                  sleep_dur = (pull_delay + end) - System.currentTimeMillis()/1000;
                else
                  sleep_dur = 0;
             }
            else
             {
                log.warn("XML Parsing/Reading Error in Volicom Data");
                sleep_dur = 60;
                // And try repeat the pull.
             }

            reader.close();  
            rc.disconnect();  
           }
          catch(Exception e)
           {
            log.error("Error in Communicating to Volicon Server :" + e2str(e));
            log.info("sleeping (5 min) before retry..");
            Thread.sleep(5 * 1000);
           }
  
           if ( sleep_dur > 0 )
            {
             log.info("Sleeping (seconds) before next try/pull: " + sleep_dur);
             Thread.sleep(sleep_dur *1000);
            }
         } // endless-while

      } // main()

      /**
       * create memmap 
       */

      private static MappedByteBuffer getRestartDt() throws java.lang.Exception
      {

        int dtSz = Long.SIZE / Byte.SIZE;

        RandomAccessFile memoryMappedFile = new RandomAccessFile(work_loc + File.separator +  "voliconTracker.mem", "rw");
        MappedByteBuffer out = memoryMappedFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, dtSz);
        log.info("Restart DtTm : " + out.getLong(0));

        return out;
      }

      /** 
       * function helper to log stack trace to log file.
       */

      public static String e2str(Exception e)
      {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String exceptionDetails = sw.toString();
        return exceptionDetails;
      }

      public static void setupEnv(String args[]) throws java.lang.Exception
      {
       	// Obtain properties/Env params.
      	for(int i = 0; i < args.length; i++)
    	{
    		if ( args[i].compareTo("-config") == 0)
    		{
    			if (++i >= args.length)
    			{
    				System.err.println("-config not defined");
    				System.exit(1);
    			}
    			
    			config_file = args[i];
    		}
    		else
    		{
    			System.err.println("Invalid parameter:" + args[i]);
    			System.exit(1);
    		}
    	}
    	    	
    	if ( config_file.isEmpty() )    		 
    	{
    		System.err.println("Configuration file (-config) not defined");
    		System.exit(1);
    	}
    	else
    	{
    	  Properties settings = new Properties();  
    	  
    	  try
    	  {
    		 FileInputStream in = new FileInputStream(config_file);
    		 settings.load(in);    		 
    	  }
    	  catch(Exception e)
    	  {
    		  System.err.println("Error loading properties file:" + config_file);
    		  e.printStackTrace();
    		  System.exit(1);    		  
    	  }
    	  
    	  // populate essential params needed.
    	  
    	  service_url = settings.getProperty("service_url");

          if ( service_url.isEmpty() )
            {
             System.err.println("service_url - not defined in properties file");
             System.exit(1);
            }

    	  out_loc = settings.getProperty("output_loc");
        
          if ( out_loc.isEmpty() )
            {
             System.err.println("output_loc - not defined in properties file");
             System.exit(1);
            }

          work_loc = settings.getProperty("work_loc");
             
          if ( work_loc.isEmpty() )
            {
             System.err.println("work_loc - not defined in properties file");
             System.exit(1);
            }

          log_config = settings.getProperty("log4jprop");

    	  String dur = settings.getProperty("pull_duration_secs", "300");
    	  String delay = settings.getProperty("pull_delay_secs", "1800");
          

    	  pull_dur = new Integer(dur);
    	  pull_delay = new Integer(delay);

         }
    } // setupEnv 

    private static String getRequestMessage(long start, long end) 
            throws Exception
    {
        StringWriter sw = new StringWriter();
        XMLOutputFactory xof = XMLOutputFactory.newInstance();
        XMLStreamWriter writer = xof.createXMLStreamWriter(sw);
        writer.writeStartDocument();
        writer.writeStartElement("SOAP-ENV:Envelope");
        writer.writeAttribute("SOAP-ENV:xmlns", "http://schemas.xmlsoap.org/soap/envelope/");
        writer.writeAttribute("ns1:xmlns", NAMESPACE);        
        writer.writeStartElement("SOAP-ENV:Body");
        writer.writeStartElement("ns1:queryVisits");
        writer.writeStartElement("start");
        writer.writeCharacters(Long.toString(start)); 
        writer.writeEndElement(); // end start
        writer.writeStartElement("end");
        writer.writeCharacters(Long.toString(end)); 
        writer.writeEndElement(); // end end
        writer.writeEndElement(); // end queryVisits
        writer.writeEndElement(); // end body        
        writer.writeEndElement(); // end envelope
        writer.writeEndDocument(); // end document
        writer.flush();
        writer.close();        
        
        return sw.getBuffer().toString();
    }

    /*
     Parses the XML message from Volicom. Parse as much as we can. If any error
     encountered, log the message & at the end, dump the raw XML as well, for debugging.
    */
    
    private static boolean  readResponseMessage(InputStreamReader isr, long start, long end) throws Exception
    {
        XMLInputFactory xif = XMLInputFactory.newInstance();
        xif.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false); // volicom namespace issue.

        boolean xml_error     = false;
        boolean in_item_tag   = false;
        boolean title_needed  = true;

        BufferedWriter out = null;

        String title = "id|start|end|channel|service|result_code|result_text|command|script|audio_level|score|description|";

        try {      
          
             Date startDate = new Date(start * 1000);
             Date endDate = new Date(end * 1000);

             SimpleDateFormat dtFrmt = new SimpleDateFormat("yyyyMMddHHmmss");
             Date dt = new Date();
             String outfile = out_loc + File.separator
			+ "volicon." 
			+ dtFrmt.format(startDate) + "-" 
			+ dtFrmt.format(endDate) + "." 
			+ dtFrmt.format(dt) + ".recs";

             String tmpOutFile = outfile + ".tmp";

             FileWriter fstream = new FileWriter(tmpOutFile);
             out = new BufferedWriter(fstream);
 
             XMLStreamReader reader = xif.createXMLStreamReader(isr);

             HashMap<String,String> rec = new HashMap<String,String>();
        
             while (reader.hasNext())
             {
               int event = reader.next();
           
               switch(event)
                {
                 case XMLStreamConstants.START_ELEMENT:
                   {

                    if ( reader.getLocalName().equals("item") )
                     {
                      rec.clear(); // prepare for new record.
                      in_item_tag = true; // start parsing fields.

                      break; // important
                     }

                    if ( in_item_tag ) // in item tag
                     {
                      rec.put(reader.getLocalName(), reader.getElementText());
                     }
                   }
                 break;
            
                 case XMLStreamConstants.END_ELEMENT: 
                   {
                    if ( in_item_tag) 
                     {
                      // print the record here.
                      StringBuilder sb = new StringBuilder();
 
                      sb.append(rec.get("id"));
                      sb.append('|');
                      sb.append(rec.get("start"));
                      sb.append('|');
                      sb.append(rec.get("end"));
                      sb.append('|');
                      sb.append(rec.get("channel"));
                      sb.append('|');
                      sb.append(rec.get("service"));
                      sb.append('|');
                      sb.append(rec.get("result_code"));
                      sb.append('|');
                      sb.append(rec.get("result_text"));
                      sb.append('|');
                      sb.append(rec.get("command"));
                      sb.append('|');
                      sb.append(rec.get("script"));
                      sb.append('|');
                      sb.append(rec.get("audio_level"));
                      sb.append('|');
                      sb.append(rec.get("score"));
                      sb.append('|');
                      sb.append(rec.get("description"));
                      sb.append('|');

                      String csvRec = sb.toString();
                       
                      if ( title_needed)
                       {
                        title_needed = false;
                        out.write(title);
                        out.newLine();
                       }
                      out.write(csvRec);
                      out.newLine();
                     }

                    in_item_tag = false;
                   }
                 break;
            
                 default:
                   // ignore all other 
                 break; 
                } // switch
             } // while

            out.close();
            File tmpFile = new File(tmpOutFile);
            File finalFile = new File(outfile); 

            if ( !tmpFile.renameTo(finalFile) )
              log.warn("Output file  rename failed:" + tmpOutFile);
            else
              log.info("Volicon extract file created: " + outfile);
           
         } // try
         catch(XMLStreamException e)
         {
          xml_error = true;

          StringBuilder sb = new StringBuilder();     
        
          int ch = isr.read();  
          while( ch != -1 ){  
             sb.append((char)ch);  
             ch = isr.read();  
          }  
        
          String response = sb.toString();  
          System.out.println(response);

          e.printStackTrace();
          log.error("Error in XML Stream :" + e2str(e) );
         }  
         catch(IOException e)
         {
          log.error("Error in i/o :" + e2str(e) );
          System.exit(1);
         }
         finally
         {
          if ( out != null ) out.close();
         }

       return (!xml_error);
     }
}
