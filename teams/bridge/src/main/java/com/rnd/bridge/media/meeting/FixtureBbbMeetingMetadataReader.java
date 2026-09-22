package com.rnd.bridge.media.meeting;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses a BBB-shaped meeting-metadata sidecar - one {@code <recording>} element in the exact
 * shape BigBlueButton's documented {@code GET getRecordings} API call returns (see
 * fixtures/media/bbb-metadata.sample.xml, field-for-field copied from BBB's own documented
 * example response at https://docs.bigbluebutton.org/development/api/#get-getrecordings,
 * fetched and verified live - not from memory).
 * <p>
 * {@code <participants>} here is a COUNT ("3"), not a per-person list - BBB's public,
 * documented API has no per-participant join/leave endpoint. The only place BBB exposes that
 * is "Learning Analytics Dashboard" data, reachable only via an internal, undocumented API call
 * or a server-to-server callback this lane's local-folder trigger has no way to receive.
 * Returning an empty participants list here is accurate to the source, not a gap - there is
 * genuinely nothing to parse.
 * <p>
 * No Real* counterpart yet - same reasoning as the Zoom reader.
 */
@Component
public class FixtureBbbMeetingMetadataReader implements MeetingMetadataReader {

    @Override
    public MeetingMetadata read(Path sidecarFile) throws IOException {
        Document doc;
        try {
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(sidecarFile.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse BBB getRecordings-shaped XML: " + sidecarFile, e);
        }

        Element recording = doc.getDocumentElement();

        String meetingId = textOrNull(recording, "meetingID");
        String topic = textOrNull(recording, "name");
        Instant startedAt = epochMillisOrNull(textOrNull(recording, "startTime"));
        Instant endedAt = epochMillisOrNull(textOrNull(recording, "endTime"));

        return new MeetingMetadata(meetingId, topic, null, startedAt, endedAt, List.of());
    }

    private Element firstElement(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private String textOrNull(Element parent, String tag) {
        Element element = firstElement(parent, tag);
        return element != null ? element.getTextContent() : null;
    }

    private Instant epochMillisOrNull(String value) {
        return value == null || value.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }
}
