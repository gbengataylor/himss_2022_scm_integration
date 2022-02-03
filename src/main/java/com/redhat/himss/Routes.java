package com.redhat.himss;

import org.jboss.logging.Logger;

import io.smallrye.mutiny.operators.multi.builders.CollectionBasedMulti;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import javax.activation.DataHandler;

import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.attachment.AttachmentMessage;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.tarfile.TarSplitter;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.model.dataformat.BindyType;

/**
 * Camel route definitions.
 */
public class Routes extends RouteBuilder {

    private static final String RESPONSE_HEADER="RESPONSE_HEADER";
    private static Logger log = Logger.getLogger(Routes.class);


    public Routes() {
    }

    @Override
    public void configure() throws Exception {

        restConfiguration().bindingMode(RestBindingMode.json);



        /*****                Consume from HTTP           *************/
        rest("/sanityCheck")
                .get()
                .route()
                .setBody().constant("Good To Go!")
                .endRest();

        // curl -v -X POST -F "data=@src/test/himss/nogood/AM3X-365115-1002-1-2021285.txt" localhost:8180/gzippedFiles
        // curl -v -X POST -F "data=@src/test/himss/good/AM3X-365115-2021285.tgz" localhost:8180/gzippedFiles
        // curl -v -X POST -F "data=@src/test/himss/good/AM3X-365115-2021285.tgz" -F "data=@src/test/himss/good/DDAS-365115-2021285.tgz" localhost:8180/gzippedFiles
        rest("/gzippedFiles")
          .description("Consume Gzipped Files")
          .consumes("multipart/form-data")
          .post()
          .to("direct:verifyPayload");
        
        from("direct:verifyPayload")
          .routeId("verifyPayload")
          .setHeader(RESPONSE_HEADER, constant("ALL FILES PERSISTED"))
          .doTry()
                .process(new PayloadValidator())
                .process(e -> {
                    Map<String, DataHandler> attachments = e.getIn(AttachmentMessage.class).getAttachments();
                    Collection<byte[]> aList = new ArrayList<byte[]>();
                    for(Entry<String, DataHandler> attachment  : attachments.entrySet()) {
                        byte[] bytes = attachment.getValue().getInputStream().readAllBytes();
                        aList.add(bytes);
                    }
                    e.getIn().setBody(aList);
                })
                .to("seda:writeVerifiedPayloadToFilesystem")
            
          .doCatch(ValidationException.class)
                .setHeader("CamelHttpResponseCode").constant("415")
                .setHeader(RESPONSE_HEADER, exceptionMessage())
                .log(LoggingLevel.ERROR, exceptionMessage().toString())
          .doFinally()
                .setBody().header(RESPONSE_HEADER)
          .end();
            
        from("seda:writeVerifiedPayloadToFilesystem")
            .routeId("direct:writeVerifiedPayloadToFilesystem")
            .log("writeVerifiedPayloadToFilesystem")
            .split(body())
            .to("file:{{himss.scm.gzip.location}}");




    
        /*****                Consume from filesystem           *************/
        from("file:{{himss.scm.gzip.location}}?initialDelay=0&delay=1000&autoCreate=true&delete=true")
            .routeId("direct:unpackGzip")
                .unmarshal()
                .gzipDeflater()
                .log("file = ${header.CamelFileName}}")
                .split(new TarSplitter())
                  .streaming()
                  .to("direct:processTextFile")
                .end();

        from("direct:processTextFile")
            .routeId("direct:processTextFile")
            .log("file = ${header.CamelFileName}}")
            .end();


    }

    class PayloadValidator implements Processor {

        @Override
        public void process(Exchange exchange) throws ValidationException {
            AttachmentMessage attMsg = exchange.getIn(AttachmentMessage.class);
            Set<String> attachmentNames = attMsg.getAttachmentNames();
            for(String aName : attachmentNames) {
                log.info("PayloadValidator.process()   fileName = "+aName);
                if(!aName.endsWith("tgz") && !aName.endsWith("tar.gz"))
                  throw new ValidationException("Invalid file suffix: "+aName);
            }
        }
    }

    class ValidationException extends Exception {
        private static final long serialVersionUID = 1L;

        public ValidationException() {
            super();
        }

        public ValidationException(String message){
            super(message);
        }

        public ValidationException(String message, Throwable cause){
            super(message, cause);
        }
    }
}