package com.netflix.imflibrary.imp_validation.cpl;

import com.netflix.imflibrary.exceptions.IMFException;
import com.netflix.imflibrary.reader_interfaces.MXFEssenceReader;
import com.netflix.imflibrary.st2067_2.CompositionPlaylist;
import com.netflix.imflibrary.utils.ResourceByteRangeProvider;
import com.netflix.imflibrary.utils.UUIDHelper;
import org.smpte_ra.schemas.st2067_2_2013.EssenceDescriptorBaseType;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This class implements the logic to perform conformance validation of an IMF CompositionPlaylist document.
 * Conformance validation will verify that the essences that are described in the CPL EssenceDescriptionList comply with
 * IMF-CoreConstraints (SMPTE st2067-2:2013) and that the essence description contained within every essence that the CPL
 * references through its virtual track resource list is contained in the EssenceDescription List.
 */
public class CompositionPlaylistConformanceValidator {

    /**
     * This method can be used to determine if a CompositionPlaylist is conformant. Conformance checks
     * perform deeper inspection of the CompositionPlaylist.
     * @param compositionPlaylistRecord corresponding to the CompositionPlaylist
     * @return boolean to indicate of the CompositionPlaylist is conformant or not
     * @throws IOException - any I/O related error is exposed through an IOException.
     * @throws IMFException - any non compliant CPL documents will be signalled through an IMFException
     * @throws SAXException - exposes any issues with instantiating a {@link javax.xml.validation.Schema Schema} object
     * @throws JAXBException - any issues in serializing the XML document using JAXB are exposed through a JAXBException
     * @throws URISyntaxException exposes any issues instantiating a {@link java.net.URI URI} object
     */
    public boolean isCompositionPlaylistConformed(CompositionPlaylistRecord compositionPlaylistRecord) throws IOException, IMFException, SAXException, JAXBException, URISyntaxException {
        boolean result = true;
        /*
         * The algorithm for conformance checking a CompositionPlaylist (CPL) would be
         * 1) Verify that every EssenceDescriptor element in the EssenceDescriptor list is referenced through its id element
         * by at least one TrackFileResource within the Virtual tracks in the CompositionPlaylist (see section 6.1.10 of SMPTE st2067-3:2-13).
         * 2) Verify that all track file resources within a virtual track have a corresponding essence descriptor in the essence descriptor list.
         * 3) Verify that the EssenceDescriptors in the EssenceDescriptorList element in the CompositionPlaylist are present in
         * the physical essence files referenced by the resources of a virtual track.
         */
        CompositionPlaylist compositionPlaylist = compositionPlaylistRecord.getCompositionPlaylist();
        List<EssenceDescriptorBaseType> essenceDescriptorList = compositionPlaylist.getCompositionPlaylistType().getEssenceDescriptorList().getEssenceDescriptor();
        HashSet<UUID> essenceDescriptorIdsSet = new LinkedHashSet<>();
        Map<UUID, List<Node>> eDLMap = new HashMap<>();/*Map <sourceEncodingElement List<Node>> from the EssenceDescriptorList*/
        for(EssenceDescriptorBaseType essenceDescriptorBaseType : essenceDescriptorList){
            UUID sourceEncodingElement = UUIDHelper.fromUUIDAsURNStringToUUID(essenceDescriptorBaseType.getId());
            essenceDescriptorIdsSet.add(sourceEncodingElement);
            List<Node> essenceDescriptorNodes = new ArrayList<>();
            for(Object object : essenceDescriptorBaseType.getAny()){
                essenceDescriptorNodes.add((Node) object);
            }
            eDLMap.put(sourceEncodingElement, essenceDescriptorNodes);

        }

        /**
         * Get the complete list of SourceEncoding elements in all the TrackFileResources in the CPL.
         */
        List<CompositionPlaylist.VirtualTrack>virtualTracks =  CompositionPlaylistHelper.getVirtualTracks(compositionPlaylistRecord);
        LinkedHashSet<UUID> resourceSourceEncodingElementsSet = new LinkedHashSet<>();
        Map<UUID, ResourceByteRangeProvider>sourceEncodingElementByteRangeProviderMap = new HashMap<>();/*Map containing <SourceEncodingElement, ResourceByteRangeProvider> entries*/
        Map<UUID, CompositionPlaylist.SequenceTypeEnum>sourceEncodingElementToSequenceTypeMap = new HashMap<>();/*Map containing <SourceEncodingElement, SequenceType>*/
        for(CompositionPlaylist.VirtualTrack virtualTrack : virtualTracks){
            List<CompositionPlaylistHelper.ResourceIdTuple> resourceIdTuples = CompositionPlaylistHelper.getVirtualTrackResourceIDs(virtualTrack);
            Map<UUID, ResourceByteRangeProvider> imfEssenceMap = compositionPlaylistRecord.getImfEssenceMap();
            for(CompositionPlaylistHelper.ResourceIdTuple resourceIdTuple : resourceIdTuples){
                resourceSourceEncodingElementsSet.add(resourceIdTuple.getSourceEncoding());
                sourceEncodingElementByteRangeProviderMap.put(resourceIdTuple.getSourceEncoding(), imfEssenceMap.get(resourceIdTuple.getTrackFileId()));
                sourceEncodingElementToSequenceTypeMap.put(resourceIdTuple.getSourceEncoding(), virtualTrack.getSequenceTypeEnum());
            }
        }
        /*The following check simultaneously verifies 1) and 2) from above.*/
        if(!essenceDescriptorIdsSet.equals(resourceSourceEncodingElementsSet)){
            throw new IMFException(String.format("At least one of the EssenceDescriptors in the EssenceDescriptorList is not referenced by a TrackFileResource or there is at least one TrackFileResource that is not referenced by a EssenceDescriptor in the EssenceDescriptorList"));
        }

        /**
         * Creating a HashMap <sourceEncodingElement List<Node></>> from reading the metadata in the Essences, and
         * using the sourceEncodingElementByteRangeProviderMap.
         */
        Map<UUID, List<Node>> essenceDescriptorMap = new LinkedHashMap<>();/*Map <sourceEncodingElement List<Node>> from the physical essence files*/
        for(UUID uuid : sourceEncodingElementByteRangeProviderMap.keySet()){
            MXFEssenceReader mxfEssenceReader = new MXFEssenceReader(sourceEncodingElementByteRangeProviderMap.get(uuid));
            if(essenceDescriptorMap.get(uuid) == null) {
                essenceDescriptorMap.put(uuid, mxfEssenceReader.getEssenceDescriptors());
            }
        }

        /**
         * An exhaustive compare of the eDLMap and essenceDescriptorMap is required to ensure that the essence descriptors
         * in the EssenceDescriptorList and the EssenceDescriptors in the physical essence files corresponding to the
         * same source encoding element as indicated in the TrackFileResource and EDL are a good match.
         */
        Map<UUID, Map<String, List<Map<String, String>>>> virtualTracksEssenceDescriptorsMap = this.getEssenceDescriptorsObjectModel(essenceDescriptorMap);
        Map<UUID, Map<String, List<Map<String, String>>>> cplEDLEssenceDescriptorsMap = this.getEssenceDescriptorsObjectModel(eDLMap);

        for(UUID uuid : cplEDLEssenceDescriptorsMap.keySet()){
            Map<String, List<Map<String, String>>>cplEssenceDescriptorsMap = cplEDLEssenceDescriptorsMap.get(uuid);
            Map<String, List<Map<String, String>>>essenceDescriptorsMap = virtualTracksEssenceDescriptorsMap.get(uuid);
            for(String essenceDescriptorType : cplEssenceDescriptorsMap.keySet()){
                if(essenceDescriptorsMap.get(essenceDescriptorType) != null){

                }
                else{
                    throw new IMFException(String.format("No EssenceDescriptor of type %s, is present in the essence referenced by TrackResource that has a SourceEncoding element set to %s", essenceDescriptorType, uuid.toString()));
                }
            }
        }
        return result;
    }

    private Map<UUID, Map<String, List<Map<String, String>>>> getEssenceDescriptorsObjectModel(Map<UUID, List<Node>> map){
        Map<UUID, Map<String, List<Map<String, String>>>> essenceDescriptorsMap = new LinkedHashMap<>();
        for(UUID uuid : map.keySet()){
            List<Node> cplEssenceDescriptorNodes = map.get(uuid); /*List of nodes corresponding to this essence's essence descriptors*/
            /**
             * Every essence descriptor in the CPL's EDL would be represented using a map as its object model.
             * In this case the implementation allows for multiple essence descriptors of the same type in the EDL, for e.g.
             * multiple CDCIPictureEssenceDescriptors in a single essence.
             */
            Map<String, List<Map<String, String>>> essenceDescriptorsNodeMap = new LinkedHashMap<>();
            for(Node node : cplEssenceDescriptorNodes){
                Map<String, String>domNodeMap = CompositionPlaylistHelper.getDOMNodeAsAMap(node);
                List<Map<String, String>> list = essenceDescriptorsNodeMap.get(node.getLocalName());
                if(list == null){
                    list = new ArrayList<Map<String, String>>();
                    essenceDescriptorsNodeMap.put(node.getLocalName(), list);
                }
                list.add(domNodeMap);
            }
            essenceDescriptorsMap.put(uuid, essenceDescriptorsNodeMap);
        }
        return essenceDescriptorsMap;
    }

}
