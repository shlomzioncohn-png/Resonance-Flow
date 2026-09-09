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
 * Parses a BBB-shaped meeting-metadata sidecar - metadata.xml, matching BigBlueButton's
 * documented recording metadata format (see fixtures/media/bbb-metadata.sample.xml).
 * <p>
 * metadata.xml is meeting-level only (id, name, start/end time, participant count) - it does
 * NOT carry per-participant join/leave timestamps; BBB reports those separately in events.xml,
 * which this lane doesn't read (confirmed before building this reader - see plan). Returning
 * an empty participants list here is therefore accurate to the source file, not a gap: there is
 * nothing to fabricate.
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
            throw new IOException("Failed to parse BBB metadata.xml: " + sidecarFile, e);
        }

        Element recording = doc.getDocumentElement();
        Element meeting = firstElement(recording, "meeting");

        String meetingId = meeting != null ? meeting.getAttribute("id") : textOrNull(recording, "id");
        String topic = meeting != null ? meeting.getAttribute("name") : null;
        Instant startedAt = epochMillisOrNull(textOrNull(recording, "start_time"));
        Instant endedAt = epochMillisOrNull(textOrNull(recording, "end_time"));

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
