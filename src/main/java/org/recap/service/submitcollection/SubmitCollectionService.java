package org.recap.service.submitcollection;

import org.apache.commons.collections.CollectionUtils;
import org.marc4j.marc.Record;
import org.recap.ReCAPConstants;
import org.recap.converter.MarcToBibEntityConverter;
import org.recap.converter.SCSBToBibEntityConverter;
import org.recap.converter.XmlToBibEntityConverterInterface;
import org.recap.model.*;
import org.recap.model.jaxb.BibRecord;
import org.recap.model.jaxb.JAXBHandler;
import org.recap.model.jaxb.marc.BibRecords;
import org.recap.model.report.SubmitCollectionReportInfo;
import org.recap.repository.*;
import org.recap.util.MarcUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;
import javax.xml.bind.JAXBException;
import java.util.*;

/**
 * Created by premkb on 20/12/16.
 */
@Service
public class SubmitCollectionService {

    private static final Logger logger = LoggerFactory.getLogger(SubmitCollectionService.class);

    @Autowired
    private BibliographicDetailsRepository bibliographicDetailsRepository;

    @Autowired
    private CustomerCodeDetailsRepository customerCodeDetailsRepository;

    @Autowired
    private ItemDetailsRepository itemDetailsRepository;

    @Autowired
    private MarcToBibEntityConverter marcToBibEntityConverter;

    @Autowired
    private SCSBToBibEntityConverter scsbToBibEntityConverter;

    @Autowired
    private ReportDetailRepository reportDetailRepository;

    @Autowired
    private ItemStatusDetailsRepository itemStatusDetailsRepository;

    @Autowired
    private InstitutionDetailsRepository institutionDetailsRepository;

    @Autowired
    private MarcUtil marcUtil;

    @PersistenceContext
    private EntityManager entityManager;

    private RestTemplate restTemplate;

    private Map<Integer,String> itemStatusMap;

    private Map<Integer,String> institutionEntityMap;

    @Value("${server.protocol}")
    private String serverProtocol;

    @Value("${scsb.solr.client.url}")
    private String scsbSolrClientUrl;

    @Value("${submit.collection.input.limit}")
    private Integer inputLimit;

    @Transactional
    public String process(String inputRecords, List<Integer> processedBibIdList,Map<String,String> idMapToRemoveIndex,String xmlFileName) {
        String reponse = null;
        Map<String,List<SubmitCollectionReportInfo>> submitCollectionReportInfoMap = getSubmitCollectionReportMap();
        try{
            if(!"".equals(inputRecords)) {
                if (inputRecords.contains(ReCAPConstants.BIBRECORD_TAG)) {
                    reponse = processSCSB(inputRecords, processedBibIdList, submitCollectionReportInfoMap,idMapToRemoveIndex);
                    if (reponse != null)
                        return reponse;
                } else {
                    reponse = processMarc(inputRecords, processedBibIdList, submitCollectionReportInfoMap,idMapToRemoveIndex);
                    if (reponse != null)
                        return reponse;
                }
                generateSubmitCollectionReport(submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_SUCCESS_LIST),ReCAPConstants.SUBMIT_COLLECTION_REPORT,ReCAPConstants.SUBMIT_COLLECTION_SUCCESS_REPORT,xmlFileName);
                generateSubmitCollectionReport(submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_FAILURE_LIST),ReCAPConstants.SUBMIT_COLLECTION_REPORT,ReCAPConstants.SUBMIT_COLLECTION_FAILURE_REPORT,xmlFileName);
                generateSubmitCollectionReport(submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_REJECTION_LIST),ReCAPConstants.SUBMIT_COLLECTION_REPORT,ReCAPConstants.SUBMIT_COLLECTION_REJECTION_REPORT,xmlFileName);
                generateSubmitCollectionReport(submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_EXCEPTION_LIST),ReCAPConstants.SUBMIT_COLLECTION_REPORT,ReCAPConstants.SUBMIT_COLLECTION_EXCEPTION_REPORT,xmlFileName);
                reponse = getResponseMessage(submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_REJECTION_LIST),submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_EXCEPTION_LIST),processedBibIdList);
            }
        }catch (Exception e){
            logger.error(ReCAPConstants.LOG_ERROR,e);
            reponse = ReCAPConstants.SUBMIT_COLLECTION_INTERNAL_ERROR;
        }
        return reponse;
    }

    private String getResponseMessage(List<SubmitCollectionReportInfo> submitCollectionRejectionInfos,List<SubmitCollectionReportInfo> submitCollectionExceptionInfos,
                                      List<Integer> processedBibIdList){
        String responseMessage;
        if(!processedBibIdList.isEmpty()){
            responseMessage = ReCAPConstants.SUCCESS+", "+ReCAPConstants.SUMBIT_COLLECTION_UPDATE_MESSAGE;
        } else{
            responseMessage = ReCAPConstants.SUMBIT_COLLECTION_NOT_UPDATED_MESSAGE;
        }
        if(!submitCollectionRejectionInfos.isEmpty()){
            if (null != responseMessage) {
                responseMessage = responseMessage + ", " +ReCAPConstants.SUBMIT_COLLECTION_REJECTION_REPORT_MESSAGE;
            } else {
                responseMessage = ReCAPConstants.SUBMIT_COLLECTION_REJECTION_REPORT_MESSAGE;
            }
        }
        if(!submitCollectionExceptionInfos.isEmpty()){
            if(null != responseMessage){
                responseMessage = responseMessage + ", " +ReCAPConstants.SUBMIT_COLLECTION_EXCEPTION_REPORT_MESSAGE;
            } else{
                responseMessage = ReCAPConstants.SUBMIT_COLLECTION_EXCEPTION_REPORT_MESSAGE;
            }
        }
        return responseMessage;
    }

    private String processMarc(String inputRecords, List<Integer> processedBibIdList,Map<String,List<SubmitCollectionReportInfo>> submitCollectionReportInfoMap,Map<String,String> idMapToRemoveIndex) {
        String format;
        format = ReCAPConstants.FORMAT_MARC;
        List<Record> records = null;
        try {
            records = getMarcUtil().convertMarcXmlToRecord(inputRecords);
            if(records.size() > inputLimit){
                return ReCAPConstants.SUBMIT_COLLECTION_LIMIT_EXCEED_MESSAGE + inputLimit;
            }
        } catch (Exception e) {
            logger.info(String.valueOf(e.getCause()));
            logger.error(ReCAPConstants.LOG_ERROR,e);
            return ReCAPConstants.INVALID_MARC_XML_FORMAT_MESSAGE;
        }
        if (CollectionUtils.isNotEmpty(records)) {
            for (Record record : records) {
                BibliographicEntity bibliographicEntity = loadData(record, format, submitCollectionReportInfoMap,idMapToRemoveIndex);
                if (null!=bibliographicEntity && null != bibliographicEntity.getBibliographicId()) {
                    processedBibIdList.add(bibliographicEntity.getBibliographicId());
                }
            }
        }
        return null;
    }

    private String processSCSB(String inputRecords, List<Integer> processedBibIdList, Map<String,List<SubmitCollectionReportInfo>> submitCollectionReportInfoMap, Map<String, String> idMapToRemoveIndex) {
        String format;
        format = ReCAPConstants.FORMAT_SCSB;
        BibRecords bibRecords = null;
        try {
            bibRecords = (BibRecords) JAXBHandler.getInstance().unmarshal(inputRecords, BibRecords.class);
            logger.info("bibrecord size {}", bibRecords.getBibRecords().size());
            if (bibRecords.getBibRecords().size() > inputLimit) {
                return ReCAPConstants.SUBMIT_COLLECTION_LIMIT_EXCEED_MESSAGE + " " + inputLimit;
            }
        } catch (JAXBException e) {
            logger.info(String.valueOf(e.getCause()));
            logger.error(ReCAPConstants.LOG_ERROR, e);
            return ReCAPConstants.INVALID_SCSB_XML_FORMAT_MESSAGE;
        }
        for (BibRecord bibRecord : bibRecords.getBibRecords()) {
            BibliographicEntity bibliographicEntity = loadData(bibRecord, format, submitCollectionReportInfoMap, idMapToRemoveIndex);
            if (null!=bibliographicEntity && null != bibliographicEntity.getBibliographicId()) {
                processedBibIdList.add(bibliographicEntity.getBibliographicId());
            }
        }
        return null;
    }


    private BibliographicEntity loadData(Object record, String format, Map<String,List<SubmitCollectionReportInfo>> submitCollectionReportInfoMap,Map<String,String> idMapToRemoveIndex){
        BibliographicEntity savedBibliographicEntity = null;
        Map responseMap = getConverter(format).convert(record);
        BibliographicEntity bibliographicEntity = (BibliographicEntity) responseMap.get("bibliographicEntity");
        List<ReportEntity> reportEntityList = (List<ReportEntity>) responseMap.get("reportEntities");
        if (CollectionUtils.isNotEmpty(reportEntityList)) {
            reportDetailRepository.save(reportEntityList);
        }
        if (bibliographicEntity != null) {
            savedBibliographicEntity = updateBibliographicEntity(bibliographicEntity, submitCollectionReportInfoMap,idMapToRemoveIndex);
        }
        return savedBibliographicEntity;
    }

    public void setSubmitCollectionRejectionInfo(BibliographicEntity bibliographicEntity,List<SubmitCollectionReportInfo> submitCollectionRejectionInfos){
        for(ItemEntity itemEntity : bibliographicEntity.getItemEntities()){
            ItemStatusEntity itemStatusEntity = getItemStatusDetailsRepository().findByItemStatusId(itemEntity.getItemAvailabilityStatusId());
            if(!itemStatusEntity.getStatusCode().equalsIgnoreCase(ReCAPConstants.ITEM_STATUS_AVAILABLE)){
                SubmitCollectionReportInfo submitCollectionRejectionInfo = new SubmitCollectionReportInfo();
                submitCollectionRejectionInfo.setItemBarcode(itemEntity.getBarcode());
                submitCollectionRejectionInfo.setCustomerCode(itemEntity.getCustomerCode());
                submitCollectionRejectionInfo.setOwningInstitution((String) getInstitutionEntityMap().get(itemEntity.getOwningInstitutionId()));
                submitCollectionRejectionInfo.setMessage(ReCAPConstants.SUBMIT_COLLECTION_REJECTION_RECORD);
                submitCollectionRejectionInfos.add(submitCollectionRejectionInfo);
            }
        }
    }

    private void setSubmitCollectionReportInfo(BibliographicEntity bibliographicEntity, List<SubmitCollectionReportInfo> submitCollectionExceptionInfos, String message) {
        for (ItemEntity itemEntity : bibliographicEntity.getItemEntities()) {
            SubmitCollectionReportInfo submitCollectionExceptionInfo = new SubmitCollectionReportInfo();
            submitCollectionExceptionInfo.setItemBarcode(itemEntity.getBarcode());
            submitCollectionExceptionInfo.setCustomerCode(itemEntity.getCustomerCode());
            submitCollectionExceptionInfo.setOwningInstitution((String) getInstitutionEntityMap().get(itemEntity.getOwningInstitutionId()));
            submitCollectionExceptionInfo.setMessage(message);
            submitCollectionExceptionInfos.add(submitCollectionExceptionInfo);
        }
    }

    public String indexData(List<Integer> bibliographicIdList){
        return getRestTemplate().postForObject(serverProtocol + scsbSolrClientUrl + "solrIndexer/indexByBibliographicId", bibliographicIdList, String.class);
    }

    public String removeSolrIndex(Map idMapToRemoveIndex){
        return getRestTemplate().postForObject(serverProtocol + scsbSolrClientUrl + "solrIndexer/deleteByBibHoldingItemId", idMapToRemoveIndex, String.class);
    }

    public BibliographicEntity updateBibliographicEntity(BibliographicEntity bibliographicEntity,Map<String,List<SubmitCollectionReportInfo>> submitCollectionReportInfoMap,Map<String,String> idMapToRemoveIndex) {
        BibliographicEntity savedBibliographicEntity;
        BibliographicEntity fetchBibliographicEntity = getBibEntityUsingBarcode(bibliographicEntity);
        if(fetchBibliographicEntity != null ){//update exisiting complete record
            if(fetchBibliographicEntity.getOwningInstitutionBibId().equals(bibliographicEntity.getOwningInstitutionBibId())){
                savedBibliographicEntity = updateCompleteRecord(fetchBibliographicEntity,bibliographicEntity,submitCollectionReportInfoMap);
            } else {//update existing incomplete record if any
                idMapToRemoveIndex.put(ReCAPConstants.BIB_ID,String.valueOf(fetchBibliographicEntity.getBibliographicId()));
                idMapToRemoveIndex.put(ReCAPConstants.HOLDING_ID,String.valueOf(fetchBibliographicEntity.getHoldingsEntities().get(0).getHoldingsId()));
                idMapToRemoveIndex.put(ReCAPConstants.ITEM_ID,String.valueOf(fetchBibliographicEntity.getItemEntities().get(0).getItemId()));
                getBibliographicDetailsRepository().delete(fetchBibliographicEntity);
                getBibliographicDetailsRepository().flush();
                savedBibliographicEntity = getBibliographicDetailsRepository().saveAndFlush(bibliographicEntity);
                entityManager.refresh(savedBibliographicEntity);
            }
        } else {//if no record found to update, generated exception info
            savedBibliographicEntity = bibliographicEntity;
            setSubmitCollectionReportInfo(bibliographicEntity,submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_EXCEPTION_LIST),ReCAPConstants.SUBMIT_COLLECTION_EXCEPTION_RECORD);
        }
        return savedBibliographicEntity;
    }

    private BibliographicEntity getBibEntityUsingBarcode(BibliographicEntity bibliographicEntity) {
        //String itemBarcode = bibliographicEntity.getItemEntities().get(0).getBarcode();
        List<String> itemBarcodeList = new ArrayList<>();
        for(ItemEntity itemEntity: bibliographicEntity.getItemEntities()){
            itemBarcodeList.add(itemEntity.getBarcode());
        }
        List<ItemEntity> itemEntityList = getItemDetailsRepository().findByBarcodeIn(itemBarcodeList);
        BibliographicEntity fetchedBibliographicEntity = null;
        if(itemEntityList != null && !itemEntityList.isEmpty() && itemEntityList.get(0).getBibliographicEntities() != null){
            fetchedBibliographicEntity = itemEntityList.get(0).getBibliographicEntities().get(0);
        }
        return fetchedBibliographicEntity;
    }

    private BibliographicEntity updateCompleteRecord(BibliographicEntity fetchBibliographicEntity,BibliographicEntity bibliographicEntity,
                                                     Map<String,List<SubmitCollectionReportInfo>> submitCollectionReportInfoMap) {
        BibliographicEntity savedOrUnsavedBibliographicEntity;
        setSubmitCollectionRejectionInfo(fetchBibliographicEntity, submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_REJECTION_LIST));
        copyBibliographicEntity(fetchBibliographicEntity, bibliographicEntity);
        List<HoldingsEntity> fetchHoldingsEntities = fetchBibliographicEntity.getHoldingsEntities();
        List<HoldingsEntity> holdingsEntities = new ArrayList<>(bibliographicEntity.getHoldingsEntities());
        for (Iterator iholdings = holdingsEntities.iterator(); iholdings.hasNext(); ) {
            HoldingsEntity holdingsEntity = (HoldingsEntity) iholdings.next();
            for (int j = 0; j < fetchHoldingsEntities.size(); j++) {
                HoldingsEntity fetchHolding = fetchHoldingsEntities.get(j);
                if (fetchHolding.getOwningInstitutionHoldingsId().equalsIgnoreCase(holdingsEntity.getOwningInstitutionHoldingsId())) {
                    copyHoldingsEntity(fetchHolding, holdingsEntity);
                    iholdings.remove();
                } else {
                    List<ItemEntity> fetchedItemEntityList = fetchHolding.getItemEntities();
                    List<ItemEntity> itemEntityList = holdingsEntity.getItemEntities();
                    for (ItemEntity fetchedItemEntity : fetchedItemEntityList) {
                        for (ItemEntity itemEntity : itemEntityList) {
                            if (fetchedItemEntity.getOwningInstitutionItemId().equals(itemEntity.getOwningInstitutionItemId())) {
                                copyHoldingsEntity(fetchHolding, holdingsEntity);
                                iholdings.remove();
                            }
                        }
                    }
                }
            }
        }
        fetchHoldingsEntities.addAll(holdingsEntities);
        // Item
        List<ItemEntity> fetchItemsEntities = fetchBibliographicEntity.getItemEntities();
        List<ItemEntity> itemsEntities = new ArrayList<>(bibliographicEntity.getItemEntities());
        for (Iterator iItems = itemsEntities.iterator(); iItems.hasNext(); ) {
            ItemEntity itemEntity = (ItemEntity) iItems.next();
            for (Iterator ifetchItems = fetchItemsEntities.iterator(); ifetchItems.hasNext(); ) {
                ItemEntity fetchItem = (ItemEntity) ifetchItems.next();
                if (fetchItem.getOwningInstitutionItemId().equalsIgnoreCase(itemEntity.getOwningInstitutionItemId())) {
                    copyItemEntity(fetchItem, itemEntity);
                    iItems.remove();
                }
            }
        }
        fetchItemsEntities.addAll(itemsEntities);
        fetchBibliographicEntity.setHoldingsEntities(fetchHoldingsEntities);
        fetchBibliographicEntity.setItemEntities(fetchItemsEntities);
        try {
            savedOrUnsavedBibliographicEntity = bibliographicDetailsRepository.saveAndFlush(fetchBibliographicEntity);
            setSubmitCollectionReportInfo(fetchBibliographicEntity,submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_SUCCESS_LIST),ReCAPConstants.SUBMIT_COLLECTION_SUCCESS_RECORD);
            return savedOrUnsavedBibliographicEntity;
        } catch (Exception e) {
            setSubmitCollectionReportInfo(fetchBibliographicEntity,submitCollectionReportInfoMap.get(ReCAPConstants.SUBMIT_COLLECTION_FAILURE_LIST),ReCAPConstants.SUBMIT_COLLECTION_FAILED_RECORD);
            logger.error(ReCAPConstants.LOG_ERROR,e);
            return null;
        }
    }

    private BibliographicEntity copyBibliographicEntity(BibliographicEntity fetchBibliographicEntity,BibliographicEntity bibliographicEntity){
        fetchBibliographicEntity.setContent(bibliographicEntity.getContent());
        fetchBibliographicEntity.setDeleted(bibliographicEntity.isDeleted());
        fetchBibliographicEntity.setLastUpdatedBy(bibliographicEntity.getLastUpdatedBy());
        fetchBibliographicEntity.setLastUpdatedDate(bibliographicEntity.getLastUpdatedDate());
        fetchBibliographicEntity.setCatalogingStatus(ReCAPConstants.COMPLETE_STATUS);
        return fetchBibliographicEntity;
    }

    private HoldingsEntity copyHoldingsEntity(HoldingsEntity fetchHoldingsEntity, HoldingsEntity holdingsEntity){
        fetchHoldingsEntity.setContent(holdingsEntity.getContent());
        fetchHoldingsEntity.setDeleted(holdingsEntity.isDeleted());
        fetchHoldingsEntity.setLastUpdatedBy(holdingsEntity.getLastUpdatedBy());
        fetchHoldingsEntity.setLastUpdatedDate(holdingsEntity.getLastUpdatedDate());
        return fetchHoldingsEntity;
    }

    private ItemEntity copyItemEntity(ItemEntity fetchItemEntity, ItemEntity itemEntity) {
        fetchItemEntity.setBarcode(itemEntity.getBarcode());
        fetchItemEntity.setDeleted(itemEntity.isDeleted());
        fetchItemEntity.setLastUpdatedBy(itemEntity.getLastUpdatedBy());
        fetchItemEntity.setLastUpdatedDate(itemEntity.getLastUpdatedDate());
        fetchItemEntity.setCallNumber(itemEntity.getCallNumber());
        fetchItemEntity.setCallNumberType(itemEntity.getCallNumberType());
        if (isAvailableItem(fetchItemEntity.getItemAvailabilityStatusId())) {
            fetchItemEntity.setCollectionGroupId(itemEntity.getCollectionGroupId());
            fetchItemEntity.setUseRestrictions(itemEntity.getUseRestrictions());
        }
        fetchItemEntity.setCopyNumber(itemEntity.getCopyNumber());
        fetchItemEntity.setVolumePartYear(itemEntity.getVolumePartYear());
        fetchItemEntity.setCatalogingStatus(ReCAPConstants.COMPLETE_STATUS);
        return fetchItemEntity;
    }

    public boolean isAvailableItem(Integer itemAvailabilityStatusId){
        String itemStatusCode = (String) getItemStatusMap().get(itemAvailabilityStatusId);
        if (itemStatusCode.equalsIgnoreCase(ReCAPConstants.ITEM_STATUS_AVAILABLE)) {
            return true;
        }
        return false;
    }

    private XmlToBibEntityConverterInterface getConverter(String format){
        if(format.equalsIgnoreCase(ReCAPConstants.FORMAT_MARC)){
            return marcToBibEntityConverter;
        } else if(format.equalsIgnoreCase(ReCAPConstants.FORMAT_SCSB)){
            return scsbToBibEntityConverter;
        }
        return null;
    }

    public void generateSubmitCollectionReport(List<SubmitCollectionReportInfo> submitCollectionReportList,String fileName, String reportType,String xmlFileName){
        if(submitCollectionReportList != null && !submitCollectionReportList.isEmpty()){
            try {
                ReportEntity reportEntity = new ReportEntity();
                List<ReportDataEntity> reportDataEntities = new ArrayList<>();
                String owningInstitution = submitCollectionReportList.get(0).getOwningInstitution();
                if(!submitCollectionReportList.isEmpty()){
                    reportEntity.setFileName(fileName+"-"+xmlFileName);
                    reportEntity.setType(reportType);
                    reportEntity.setCreatedDate(new Date());
                    reportEntity.setInstitutionName(owningInstitution);
                }
                for(SubmitCollectionReportInfo submitCollectionReportInfo : submitCollectionReportList){
                    if(!StringUtils.isEmpty(submitCollectionReportInfo.getItemBarcode()) && !StringUtils.isEmpty(submitCollectionReportInfo.getCustomerCode())){

                        ReportDataEntity itemBarcodeReportDataEntity = new ReportDataEntity();
                        itemBarcodeReportDataEntity.setHeaderName(ReCAPConstants.SUBMIT_COLLECTION_ITEM_BARCODE);
                        itemBarcodeReportDataEntity.setHeaderValue(submitCollectionReportInfo.getItemBarcode());
                        reportDataEntities.add(itemBarcodeReportDataEntity);

                        ReportDataEntity customerCodeReportDataEntity = new ReportDataEntity();
                        customerCodeReportDataEntity.setHeaderName(ReCAPConstants.SUBMIT_COLLECTION_CUSTOMER_CODE);
                        customerCodeReportDataEntity.setHeaderValue(submitCollectionReportInfo.getCustomerCode());
                        reportDataEntities.add(customerCodeReportDataEntity);

                        ReportDataEntity owningInstitutionReportDataEntity = new ReportDataEntity();
                        owningInstitutionReportDataEntity.setHeaderName(ReCAPConstants.OWNING_INSTITUTION);
                        owningInstitutionReportDataEntity.setHeaderValue(owningInstitution);
                        reportDataEntities.add(owningInstitutionReportDataEntity);

                        ReportDataEntity messageReportDataEntity = new ReportDataEntity();
                        messageReportDataEntity.setHeaderName(ReCAPConstants.MESSAGE);
                        messageReportDataEntity.setHeaderValue(submitCollectionReportInfo.getMessage());
                        reportDataEntities.add(messageReportDataEntity);

                        reportEntity.setReportDataEntities(reportDataEntities);
                        reportDetailRepository.save(reportEntity);

                    }
                }
            } catch (Exception e) {
                logger.error(ReCAPConstants.LOG_ERROR,e);
            }
        }
    }

    public BibliographicDetailsRepository getBibliographicDetailsRepository() {
        return bibliographicDetailsRepository;
    }

    public void setBibliographicDetailsRepository(BibliographicDetailsRepository bibliographicDetailsRepository) {
        this.bibliographicDetailsRepository = bibliographicDetailsRepository;
    }

    public CustomerCodeDetailsRepository getCustomerCodeDetailsRepository() {
        return customerCodeDetailsRepository;
    }

    public void setCustomerCodeDetailsRepository(CustomerCodeDetailsRepository customerCodeDetailsRepository) {
        this.customerCodeDetailsRepository = customerCodeDetailsRepository;
    }

    public MarcUtil getMarcUtil() {
        return marcUtil;
    }

    public void setMarcUtil(MarcUtil marcUtil) {
        this.marcUtil = marcUtil;
    }

    public ItemDetailsRepository getItemDetailsRepository() {
        return itemDetailsRepository;
    }

    public void setItemDetailsRepository(ItemDetailsRepository itemDetailsRepository) {
        this.itemDetailsRepository = itemDetailsRepository;
    }

    public ItemStatusDetailsRepository getItemStatusDetailsRepository() {
        return itemStatusDetailsRepository;
    }

    public void setItemStatusDetailsRepository(ItemStatusDetailsRepository itemStatusDetailsRepository) {
        this.itemStatusDetailsRepository = itemStatusDetailsRepository;
    }

    public InstitutionDetailsRepository getInstitutionDetailsRepository() {
        return institutionDetailsRepository;
    }

    public void setInstitutionDetailsRepository(InstitutionDetailsRepository institutionDetailsRepository) {
        this.institutionDetailsRepository = institutionDetailsRepository;
    }

    public EntityManager getEntityManager() {
        return entityManager;
    }

    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public RestTemplate getRestTemplate() {
        if(restTemplate == null){
            restTemplate = new RestTemplate();
        }
        return restTemplate;
    }

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Map getItemStatusMap() {
        if (null == itemStatusMap) {
            itemStatusMap = new HashMap();
            try {
                Iterable<ItemStatusEntity> itemStatusEntities = itemStatusDetailsRepository.findAll();
                for (Iterator iterator = itemStatusEntities.iterator(); iterator.hasNext(); ) {
                    ItemStatusEntity itemStatusEntity = (ItemStatusEntity) iterator.next();
                    itemStatusMap.put(itemStatusEntity.getItemStatusId(), itemStatusEntity.getStatusCode());
                }
            } catch (Exception e) {
                logger.error(ReCAPConstants.LOG_ERROR,e);            }
        }
        return itemStatusMap;
    }

    public Map getInstitutionEntityMap() {
        if (null == institutionEntityMap) {
            institutionEntityMap = new HashMap();
            try {
                Iterable<InstitutionEntity> institutionEntities = institutionDetailsRepository.findAll();
                for (Iterator iterator = institutionEntities.iterator(); iterator.hasNext(); ) {
                    InstitutionEntity institutionEntity = (InstitutionEntity) iterator.next();
                    institutionEntityMap.put(institutionEntity.getInstitutionId(), institutionEntity.getInstitutionCode());
                }
            } catch (Exception e) {
                logger.error(ReCAPConstants.LOG_ERROR,e);
            }
        }
        return institutionEntityMap;
    }

    private Map getSubmitCollectionReportMap(){
        List<SubmitCollectionReportInfo> submitCollectionSuccessInfoList = new ArrayList<>();
        List<SubmitCollectionReportInfo> submitCollectionFailureInfoList = new ArrayList<>();
        List<SubmitCollectionReportInfo> submitCollectionRejectionInfoList = new ArrayList<>();
        List<SubmitCollectionReportInfo> submitCollectionExceptionInfoList = new ArrayList<>();
        Map<String,List<SubmitCollectionReportInfo>> submitCollectionReportInfoMap = new HashMap<>();
        submitCollectionReportInfoMap.put(ReCAPConstants.SUBMIT_COLLECTION_SUCCESS_LIST,submitCollectionSuccessInfoList);
        submitCollectionReportInfoMap.put(ReCAPConstants.SUBMIT_COLLECTION_FAILURE_LIST,submitCollectionFailureInfoList);
        submitCollectionReportInfoMap.put(ReCAPConstants.SUBMIT_COLLECTION_REJECTION_LIST,submitCollectionRejectionInfoList);
        submitCollectionReportInfoMap.put(ReCAPConstants.SUBMIT_COLLECTION_EXCEPTION_LIST,submitCollectionExceptionInfoList);
        return submitCollectionReportInfoMap;
    }
}