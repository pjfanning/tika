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
package org.apache.tika.parser.pkg;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.TikaTest;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;

/**
 * Parent class for all Package based Test cases
 */
public abstract class AbstractPkgTest extends TikaTest {
    protected ParseContext trackingContext;
    protected ParseContext monitoringContext;
    protected ParseContext recursingContext;

    protected EmbeddedTrackingParser tracker;
    protected EmbeddedMonitoringParser monitor;

    @BeforeEach
    public void setUp() throws Exception {
        tracker = new EmbeddedTrackingParser();
        trackingContext = new ParseContext();
        trackingContext.set(Parser.class, tracker);

        monitor = new EmbeddedMonitoringParser(AUTO_DETECT_PARSER);
        monitoringContext = new ParseContext();
        monitoringContext.set(Parser.class, monitor);

        recursingContext = new ParseContext();
        recursingContext.set(Parser.class, AUTO_DETECT_PARSER);
    }

    protected static class EmbeddedMonitoringParser implements Parser {
        protected Parser parser;
        protected List<String> filenames = new ArrayList<>();
        protected List<String> mediaTypes = new ArrayList<>();

        public EmbeddedMonitoringParser(Parser parser) {
            this.parser = parser;
        }

        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return parser.getSupportedTypes(context);
        }

        public void reset() {
            filenames.clear();
            mediaTypes.clear();
        }

        public void parse(InputStream stream, ContentHandler handler, Metadata metadata,
                          ParseContext context) throws IOException, SAXException, TikaException {
            parser.parse(stream, handler, metadata, context);

            filenames.add(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
            mediaTypes.add(metadata.get(Metadata.CONTENT_TYPE));
        }
    }

    protected static class EmbeddedTrackingParser implements Parser {
        protected List<String> filenames = new ArrayList<>();
        protected List<String> mediatypes = new ArrayList<>();
        protected List<String> createdAts = new ArrayList<>();
        protected List<String> modifiedAts = new ArrayList<>();
        protected byte[] lastSeenStart;

        public void reset() {
            filenames.clear();
            mediatypes.clear();
            createdAts.clear();
            modifiedAts.clear();
        }

        public Set<MediaType> getSupportedTypes(ParseContext context) {
            // Cheat!
            return AUTO_DETECT_PARSER.getSupportedTypes(context);
        }

        public void parse(InputStream stream, ContentHandler handler, Metadata metadata,
                          ParseContext context) throws IOException, SAXException, TikaException {
            filenames.add(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
            mediatypes.add(metadata.get(Metadata.CONTENT_TYPE));
            createdAts.add(metadata.get(TikaCoreProperties.CREATED));
            modifiedAts.add(metadata.get(TikaCoreProperties.MODIFIED));

            lastSeenStart = new byte[32];
            stream.read(lastSeenStart);
        }

    }
}
