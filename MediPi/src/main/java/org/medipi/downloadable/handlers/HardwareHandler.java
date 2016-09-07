/*
 Copyright 2016  Richard Robinson @ HSCIC <rrobinson@hscic.gov.uk, rrobinson@nhs.net>

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package org.medipi.downloadable.handlers;

import com.nimbusds.jose.JWSObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import org.apache.commons.io.IOUtils;
import org.medipi.logging.MediPiLogger;
import org.medipi.security.CertificateDefinitions;
import org.medipi.messaging.rest.RESTfulMessagingEngine;
import org.medipi.security.UploadEncryptionAdapter;
import org.medipi.model.DownloadableDO;
import org.medipi.model.Links;
import org.medipi.utilities.Utilities;

/**
 * This is an implementation of the DownloadableHandler for hardware updates. It confirms that
 * the signature is verified and that the same content has been signed. The
 * payload is inspected for the HATEOAS link, that link is then requested and
 * the file downloaded to the local specified directory
 *
 * @author rick@robinsonhq.com
 */
public class HardwareHandler implements DownloadableHandler {

    private static final String MEDIPIDOWNLOADHARDWAREDOWNLOADDIR = "medipi.downloadable.hardware.downloaddir";
    private final Path messageDir;
    private final Properties properties;

    /**
     * Constructor: loads the download directory
     *
     * @param properties
     * @throws Exception
     */
    public HardwareHandler(Properties properties) throws Exception {
        this.properties = properties;
        //Get downbloadable directory
        String downloadableDir = properties.getProperty(MEDIPIDOWNLOADHARDWAREDOWNLOADDIR);
        if (downloadableDir == null || downloadableDir.trim().equals("")) {
            throw new Exception("MediPi directory for hardware downloadable downloads is not set");
        } else if (new File(downloadableDir).isDirectory()) {
            Path dir = Paths.get(downloadableDir);
            // Register this device with the handler
            messageDir = dir;
        } else {
            throw new Exception(downloadableDir + " - MediPi hardware dowbload directory is not a directory");
        }
    }

    @Override
    public void handle(DownloadableDO ddo) {
        try {

            // Check signature and that the payload matches
            UploadEncryptionAdapter uploadEncryptionAdapter = new UploadEncryptionAdapter();
            CertificateDefinitions cd = new CertificateDefinitions(properties);
            cd.setSIGNTRUSTSTORELOCATION("medipi.json.sign.truststore.hardware.location", CertificateDefinitions.INTERNAL);
            cd.setSIGNTRUSTSTOREPASSWORD("medipi.json.sign.truststore.hardware.password", CertificateDefinitions.INTERNAL);
            //Initialise the upload encryption Adapter
            String error = uploadEncryptionAdapter.init(cd, UploadEncryptionAdapter.VERIFYSIGNATUREMODE);
            if (error != null) {
                throw new Exception("Verify signature initailisation failed - " + error);
            }
            //create a JWS Object from the signature payload
            JWSObject jwsObject = JWSObject.parse(ddo.getSignature());
            //verify signatures to prove that the author whose provided certificate 
            //verifiably is descended from the local truststore 
            if (!uploadEncryptionAdapter.verifySignature(jwsObject)) {
                throw new Exception("Failed to resolve the author's signature");
            }
            // Build a simple concatenated representation of the DownloadableDO 
            // and compare it against the signed payload of the JWSObject
            StringBuilder constructedPayload = new StringBuilder();
            constructedPayload.append(ddo.getDownloadType())
                    .append(ddo.getDownloadableUuid())
                    .append(ddo.getFileName())
                    .append(ddo.getVersion())
                    .append(ddo.getVersionAuthor())
                    .append(ddo.getVersionDate().getTime());

            // Compare the signed payload and the contructed payload to check that 
            // that was what was signed by the original author
            if (!jwsObject.getPayload().toString().equals(constructedPayload.toString())) {
                throw new Exception("Payload hash does not match that which was signed by the author");
            }

            Response downloadResponse = null;
            RESTfulMessagingEngine rme = null;
            // Choose the link whic has the rel = next - ignore all others
            List<Links> linkList = ddo.getLinks();

            for (Links l : linkList) {
                if (l.getRel().equals("next")) {
                    System.out.println("href: " + l.getHref());
                    if (!l.getHref().isEmpty()) {
                        rme = new RESTfulMessagingEngine(l.getHref(), null);
                        downloadResponse = rme.executeGet(null);
                    }
                }
            }

            if (downloadResponse != null) {
                // Expectation is that this file will ALWAYS be a .txt file
                if (downloadResponse.getStatus() == Response.Status.OK.getStatusCode()) {
                    try {
                        MediPiLogger.getInstance().log(HardwareHandler.class.getName() + ".info", "Hardware Downloadable download started - Downloadable UUID: " + ddo.getDownloadableUuid());
                        InputStream is = downloadResponse.readEntity(InputStream.class);
                        String s = Utilities.INTERNAL_FORMAT_DATE.format(ddo.getVersionDate());
                        File f = new File(messageDir.toString(), ddo.getFileName());
                        fetchFeed(is, f);
                        IOUtils.closeQuietly(is);
                        // Depending on the type of file perform actions

                        MediPiLogger.getInstance().log(HardwareHandler.class.getName() + ".info", "Hardware Downloadable download completed - Downloadable UUID: " + ddo.getDownloadableUuid());
                        // Sucessful download now must be acked
                        // The downloadableUUID is returned in the post - not necessary but needs some payload
                        Response downloadAck = rme.executePost(null, Entity.json(ddo.getDownloadableUuid()));
                        if (downloadAck != null) {
                            if (downloadAck.getStatus() == Response.Status.OK.getStatusCode()) {
                                //No further action necessary
                                MediPiLogger.getInstance().log(HardwareHandler.class.getName() + ".info", "Hardware Downloadable download acked sucessfully - Downloadable UUID: " + ddo.getDownloadableUuid());
                            } else {
                                //FAILED TO ACK THE MESSAGE - put a message box to the patient
                                MediPiLogger.getInstance().log(HardwareHandler.class.getName() + ".error", "Hardware Downloadable download failed to ack sucessfully - Downloadable UUID: " + ddo.getDownloadableUuid());

                            }
                        }

                    } catch (Exception e) {
                        //FAILED TO SAVE THE MESSAGE - put a message box to the patient
                        MediPiLogger.getInstance().log(HardwareHandler.class.getName() + ".error", "Hardware Downloadable download failed to download or ack - probably a file issue - Downloadable UUID: " + ddo.getDownloadableUuid());

                    }
                } else {
                    //ERROR RESPONSE
                    String err = downloadResponse.readEntity(String.class);
                    switch (downloadResponse.getStatus()) {
                        // NOT FOUND
                        case 404:
                        // This is returned when the hardware name and patientId do not match
                        // ***************** DO SOMETHING WITH 404 *******************
                        // UPDATE REQUIRED
                        case 426:
                        // ***************** DO SOMETHING WITH 426 *******************
                        // INTERNAL SERVER ERROR    
                        case 500:
                        default:
                            // ***************** DO SOMETHING WITH EVERY OTHER STATUS CODE *******************
                            System.out.println(err);
                    }
                }
            } else {
                MediPiLogger.getInstance().log(HardwareHandler.class.getName() + ".error", "Hardware failed to resolve link - Downloadable UUID: " + ddo.getDownloadableUuid());
            }
        } catch (Exception e) {
            MediPiLogger.getInstance().log(HardwareHandler.class.getName() + ".error", "Hardware download failed - " + e.getLocalizedMessage() + "- Downloadable UUID: " + ddo.getDownloadableUuid());
        }
    }

    /**
     * Store contents of file from response to local disk using java 7
     * java.nio.file.Files
     */
    private void fetchFeed(InputStream is, File downloadFile) throws IOException {
        byte[] byteArray = IOUtils.toByteArray(is);
        FileOutputStream fos = new FileOutputStream(downloadFile);
        fos.write(byteArray);
        fos.flush();
        fos.close();
    }
}
