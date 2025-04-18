/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tika.server.core.resource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.tika.server.core.resource.TikaResource.fillMetadata;
import static org.apache.tika.server.core.resource.TikaResource.fillParseContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.UnsynchronizedByteArrayInputStream;
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.exception.TikaMemoryLimitException;
import org.apache.tika.extractor.DefaultEmbeddedStreamTranslator;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedStreamTranslator;
import org.apache.tika.io.BoundedInputStream;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.parser.DigestingParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.RichTextContentHandler;

@Path("/unpack")
public class UnpackerResource {
    public static final String TEXT_FILENAME = "__TEXT__";
    public static final String META_FILENAME = "__METADATA__";

    public static final String UNPACK_MAX_BYTES_KEY = "unpackMaxBytes";

    private static final long DEFAULT_MAX_ATTACHMENT_BYTES = 100 * 1024 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(UnpackerResource.class);

    public static void metadataToCsv(Metadata metadata, OutputStream outputStream) throws IOException {
        CSVPrinter writer = new CSVPrinter(new OutputStreamWriter(outputStream, UTF_8), CSVFormat.EXCEL);

        for (String name : metadata.names()) {
            String[] values = metadata.getValues(name);
            ArrayList<String> list = new ArrayList<>(values.length + 1);
            list.add(name);
            list.addAll(Arrays.asList(values));
            writer.printRecord(list);
        }

        writer.close();
    }

    @Path("/{id:(/.*)?}")
    @PUT
    @Produces({"application/zip", "application/x-tar"})
    public Map<String, byte[]> unpack(InputStream is, @Context HttpHeaders httpHeaders, @Context UriInfo info) throws Exception {
        return process(TikaResource.getInputStream(is, new Metadata(), httpHeaders, info), httpHeaders, info, false);
    }

    @Path("/all{id:(/.*)?}")
    @PUT
    @Produces({"application/zip", "application/x-tar"})
    public Map<String, byte[]> unpackAll(InputStream is, @Context HttpHeaders httpHeaders, @Context UriInfo info) throws Exception {
        return process(TikaResource.getInputStream(is, new Metadata(), httpHeaders, info), httpHeaders, info, true);
    }

    private Map<String, byte[]> process(InputStream is, @Context HttpHeaders httpHeaders, @Context UriInfo info, boolean saveAll) throws Exception {
        Metadata metadata = new Metadata();
        ParseContext pc = new ParseContext();
        long unpackMaxBytes = DEFAULT_MAX_ATTACHMENT_BYTES;
        String unpackMaxBytesString = httpHeaders
                .getRequestHeaders()
                .getFirst(UNPACK_MAX_BYTES_KEY);
        if (!StringUtils.isBlank(unpackMaxBytesString)) {
            unpackMaxBytes = Long.parseLong(unpackMaxBytesString);
            if (unpackMaxBytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Can't request value > than Integer" + ".MAX_VALUE : " + unpackMaxBytes);
            } else if (unpackMaxBytes < 0) {
                throw new IllegalArgumentException("Can't request value < 0: " + unpackMaxBytes);
            }
        }
        Parser parser = TikaResource.createParser();
        if (parser instanceof DigestingParser) {
            //no need to digest for unwrapping
            parser = ((DigestingParser) parser).getWrappedParser();
        }
        fillMetadata(parser, metadata, httpHeaders.getRequestHeaders());
        fillParseContext(httpHeaders.getRequestHeaders(), metadata, pc);

        TikaResource.logRequest(LOG, "/unpack", metadata);
        //even though we aren't currently parsing embedded documents,
        //we need to add this to allow for "inline" use of other parsers.
        pc.set(Parser.class, parser);
        ContentHandler ch;
        UnsynchronizedByteArrayOutputStream text = UnsynchronizedByteArrayOutputStream
                .builder()
                .get();

        if (saveAll) {
            ch = new BodyContentHandler(new RichTextContentHandler(new OutputStreamWriter(text, UTF_8)));
        } else {
            ch = new DefaultHandler();
        }

        Map<String, byte[]> files = new HashMap<>();
        MutableInt count = new MutableInt();

        pc.set(EmbeddedDocumentExtractor.class, new MyEmbeddedDocumentExtractor(count, files, unpackMaxBytes));

        TikaResource.parse(parser, LOG, info.getPath(), is, ch, metadata, pc);

        if (count.intValue() == 0 && !saveAll) {
            throw new WebApplicationException(Response.Status.NO_CONTENT);
        }

        if (saveAll) {
            files.put(TEXT_FILENAME, text.toByteArray());

            UnsynchronizedByteArrayOutputStream metaStream = UnsynchronizedByteArrayOutputStream
                    .builder()
                    .get();
            metadataToCsv(metadata, metaStream);

            files.put(META_FILENAME, metaStream.toByteArray());
        }

        return files;
    }

    private static class MyEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {
        private final MutableInt count;
        private final Map<String, byte[]> zout;

        private final long unpackMaxBytes;
        private final EmbeddedStreamTranslator embeddedStreamTranslator = new DefaultEmbeddedStreamTranslator();

        MyEmbeddedDocumentExtractor(MutableInt count, Map<String, byte[]> zout, long unpackMaxBytes) {
            this.count = count;
            this.zout = zout;
            this.unpackMaxBytes = unpackMaxBytes;
        }

        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(TikaInputStream tis, ContentHandler contentHandler, Metadata metadata, boolean b) throws SAXException, IOException {
            UnsynchronizedByteArrayOutputStream bos = UnsynchronizedByteArrayOutputStream
                    .builder()
                    .get();

            BoundedInputStream bis = new BoundedInputStream(unpackMaxBytes, tis);
            IOUtils.copy(bis, bos);
            if (bis.hasHitBound()) {
                throw new IOException(new TikaMemoryLimitException(
                        "An attachment is longer than " + "'unpackMaxBytes' (default=100MB, actual=" + unpackMaxBytes + "). " + "If you need to increase this " +
                                "limit, add a header to your request, such as: unpackMaxBytes: " + "1073741824.  There is a hard limit of 2GB."));
            }
            byte[] data = bos.toByteArray();

            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            String contentType = metadata.get(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE);

            if (name == null) {
                name = Integer.toString(count.intValue());
            }

            if (!name.contains(".") && contentType != null) {
                try {
                    String ext = TikaResource
                            .getConfig()
                            .getMimeRepository()
                            .forName(contentType)
                            .getExtension();

                    if (ext != null) {
                        name += ext;
                    }
                } catch (MimeTypeException e) {
                    LOG.warn("Unexpected MimeTypeException", e);
                }
            }
            try (TikaInputStream is = TikaInputStream.get(data)) {
                if (embeddedStreamTranslator.shouldTranslate(is, metadata)) {
                    InputStream translated = embeddedStreamTranslator.translate(UnsynchronizedByteArrayInputStream.builder().setByteArray(data).get(), metadata);
                    UnsynchronizedByteArrayOutputStream bos2 = UnsynchronizedByteArrayOutputStream
                            .builder()
                            .get();
                    IOUtils.copy(translated, bos2);
                    data = bos2.toByteArray();
                }
            }

            final String finalName = getFinalName(name, zout);

            if (data.length > 0) {
                zout.put(finalName, data);
                count.increment();
            }
        }

        private String getFinalName(String name, Map<String, byte[]> zout) {
            name = name.replaceAll("\u0000", " ");
            String normalizedName = FilenameUtils.normalize(name);

            if (normalizedName == null) {
                normalizedName = FilenameUtils.getName(name);
            }

            if (normalizedName == null) {
                normalizedName = count.toString();
            }
            //strip off initial C:/ or ~/ or /
            int prefixLength = FilenameUtils.getPrefixLength(normalizedName);
            if (prefixLength > -1) {
                normalizedName = normalizedName.substring(prefixLength);
            }
            if (zout.containsKey(normalizedName)) {
                return UUID
                        .randomUUID()
                        .toString() + "-" + normalizedName;
            }
            return normalizedName;
        }

/*        protected void copy(DirectoryEntry sourceDir, DirectoryEntry destDir)
                throws IOException {
            for (Entry entry : sourceDir) {
                if (entry instanceof DirectoryEntry) {
                    // Need to recurse
                    DirectoryEntry newDir = destDir.createDirectory(entry.getName());
                    copy((DirectoryEntry) entry, newDir);
                } else {
                    // Copy entry
                    try (InputStream contents = new DocumentInputStream((DocumentEntry) entry)) {
                        destDir.createDocument(entry.getName(), contents);
                    }
                }
            }
        }*/
    }
}
