/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 Adempiere, Inc. All Rights Reserved.               *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *                                                                            *
 * Copyright (C) 2004 Marco LOMBARDO. lombardo@mayking.com                    *
 * Contributor: Robert KLEIN. robeklein@hotmail.com                           *
 * Contributor: Tim Heath                                                     *
 * Contributor: Low Heng Sin  hengsin@avantz.com                              *
 *****************************************************************************/

package org.adempiere.pipo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;
import java.util.logging.Level;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import org.adempiere.pipo.handler.CodeSnipitElementHandler;
import org.adempiere.pipo.handler.CommonTranslationHandler;
import org.adempiere.pipo.handler.DataElementHandler;
import org.adempiere.pipo.handler.DistFileElementHandler;
import org.adempiere.pipo.handler.EntityTypeElementHandler;
import org.adempiere.pipo.handler.GenericPOHandler;
import org.adempiere.pipo.handler.MenuElementHandler;
import org.adempiere.pipo.handler.ModelValidatorElementHandler;
import org.adempiere.pipo.handler.ReferenceElementHandler;
import org.adempiere.pipo.handler.ReferenceListElementHandler;
import org.adempiere.pipo.handler.ReferenceTableElementHandler;
import org.adempiere.pipo.handler.SQLStatementElementHandler;
import org.adempiere.pipo.handler.TableElementHandler;
import org.adempiere.pipo.handler.WorkflowElementHandler;
import org.adempiere.pipo.handler.WorkflowNodeElementHandler;
import org.adempiere.pipo.handler.WorkflowNodeNextConditionElementHandler;
import org.adempiere.pipo.handler.WorkflowNodeNextElementHandler;
import org.compiere.model.I_AD_Column;
import org.compiere.model.I_AD_WF_Node;
import org.compiere.model.I_AD_Workflow;
import org.compiere.model.MColumn;
import org.compiere.model.MSequence;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.compiere.util.Util;
import org.compiere.wf.MWFNode;
import org.compiere.wf.MWorkflow;
import org.spin.util.XMLUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

/**
 * SAX Handler for parsing XML description of the GUI.
 *
 * @author Marco LOMBARDO, lombardo@mayking.com
 * @author Robert KLEIN, robeklein@hotmail
 * 
 * Contributor: William G. Heath - Import of workflows and dynamic validations
 */
public class PackInHandler extends DefaultHandler {

    /**
     * 	PackInHandler Handler
     */
    public PackInHandler () {
    	setupHandlers();
    }   // PackInHandler   
    
    /** Set this if you want to update Dictionary  */
    private String m_UpdateMode = "true";
    private String packageDirectory = null;
    private String m_DatabaseType = "Oracle";
    private int clientId = 0;
    private int AD_Package_Imp_ID=0;
	private int AD_Package_Imp_Inst_ID=0;
    private CLogger log = CLogger.getCLogger(PackInHandler.class);
    private OutputStream  fw_document = null;
    private TransformerHandler logDocument = null;
    private StreamResult streamResult_document = null;		
	private SAXTransformerFactory tf_document = null;	
	private Transformer serializer_document = null;
	private int Start_Doc = 0;
	private String logDate = null;
	private String PK_Status = "Installing";
	// transaction name 
	private	String 		trxName = null;
	private Properties  m_ctx = null;

	private Map<String, ElementHandler>handlers = null;
	private List<Element> menus = new ArrayList<Element>();
	private List<Element> workflow = new ArrayList<Element>();
	private List<Element> nodes = new ArrayList<Element>();
	private List<DeferEntry> defer = new ArrayList<DeferEntry>();
	private Stack<Element> stack = new Stack<Element>();
	private List<Element> columns = new ArrayList<Element>();
	private PackIn packIn;

	private void init() throws SAXException {
		if (packIn == null)
			packIn = new PackIn();
		packageDirectory = PackIn.m_Package_Dir;
		m_UpdateMode = PackIn.m_UpdateMode;
		m_DatabaseType = PackIn.m_Database;
		SimpleDateFormat formatter_file = new SimpleDateFormat("yyMMddHHmmssZ");
		SimpleDateFormat formatter_log = new SimpleDateFormat("MM/dd/yy HH:mm:ss");
		Date today = new Date();
		String fileDate = formatter_file.format(today);
		logDate = formatter_log.format(today);
		
		String file_document = packageDirectory+File.separator+"doc"+File.separator+"Importlog_"+fileDate+".xml";		
		log.info("file_document="+file_document);
		try {
			fw_document = new FileOutputStream (file_document, false);
		} catch (FileNotFoundException e1) {
			log.warning ("Failed to create log file:"+e1);
		}
		streamResult_document = new StreamResult(fw_document);		
		tf_document = (SAXTransformerFactory) SAXTransformerFactory.newInstance();	
		//	Default features
		XMLUtils.setDefaultFeatures(tf_document);
		try {
			logDocument = tf_document.newTransformerHandler();
		} catch (TransformerConfigurationException e2) {
			log.info ("startElement:"+e2);
		}		
		serializer_document = logDocument.getTransformer();		
		serializer_document.setOutputProperty(OutputKeys.ENCODING,"ISO-8859-1");		
		serializer_document.setOutputProperty(OutputKeys.INDENT,"yes");		
		logDocument.setResult(streamResult_document);				
		logDocument.startDocument();		
		logDocument.processingInstruction("xml-stylesheet","type=\"text/css\" href=\"adempiereDocument.css\"");
		Properties tmp = new Properties();
		if (m_ctx != null)
			tmp.putAll(m_ctx);
		else
			tmp.putAll(Env.getCtx());
		m_ctx = tmp;
		if (trxName == null)
			trxName = Trx.createTrxName("PackIn");
		
		clientId = Env.getContextAsInt(m_ctx, "AD_Client_ID");
		
		Start_Doc=1;
	}
	
	private void setupHandlers() {
		DataElementHandler dataHandler = new DataElementHandler();
    	handlers = new HashMap<String, ElementHandler>();
    	handlers.put("menu", new MenuElementHandler());
    	handlers.put("adempieredata", dataHandler);
    	handlers.put("data", dataHandler);
    	handlers.put("dtable", dataHandler);
    	handlers.put("drow", dataHandler);
    	handlers.put("dcolumn", dataHandler);
    	handlers.put("workflow", new WorkflowElementHandler());
    	handlers.put("workflowNode", new WorkflowNodeElementHandler());
    	handlers.put("workflowNodeNext", new WorkflowNodeNextElementHandler());
    	handlers.put("workflowNodeNextCondition", new WorkflowNodeNextConditionElementHandler());
    	handlers.put(GenericPOHandler.Column_TAG_Name, new TableElementHandler());
    	handlers.put(GenericPOHandler.TAG_Name, new GenericPOHandler());
    	handlers.put("codesnipit", new CodeSnipitElementHandler());
    	handlers.put("distfile", new DistFileElementHandler());
    	handlers.put("SQLStatement", new SQLStatementElementHandler());
    	handlers.put("reference", new ReferenceElementHandler());
    	handlers.put("referencelist", new ReferenceListElementHandler());
    	handlers.put("referencetable", new ReferenceTableElementHandler());
    	handlers.put("trl", new CommonTranslationHandler());
    	handlers.put(ModelValidatorElementHandler.TAG_Name, new ModelValidatorElementHandler());
    	handlers.put(EntityTypeElementHandler.TAG_Name, new EntityTypeElementHandler());
	}
	
    /**
     * 	Receive notification of the start of an element.
     *
     * 	@param uri namespace
     * 	@param localName simple name
     * 	@param qName qualified name
     * 	@param atts attributes
     * 	@throws org.xml.sax.SAXException
     */
	public void startElement (String uri, String localName, String qName, Attributes atts)
	throws org.xml.sax.SAXException {
		
		// Create the package log    	
		if (Start_Doc==0){
			init();
		}
		// Check namespace.
		String elementValue = null;
		if ("".equals (uri))
			elementValue = qName;
		else
			elementValue = uri + localName;
		
		// adempiereAD.	
		if (elementValue.equals("adempiereAD")) {		
			log.info("adempiereAD updateMode="+m_UpdateMode);
			//Start package log
			AttributesImpl attsOut = new AttributesImpl();
			logDocument.startElement("","","adempiereDocument",attsOut);
			logDocument.startElement("","","header",attsOut);		
			logDocument.characters((atts.getValue("Name")+" Install Log").toCharArray(),0,(atts.getValue("Name")+" Install Log").length());
			logDocument.endElement("","","header");
			logDocument.startElement("","","H3",attsOut);		
			logDocument.characters(("Package Name:" ).toCharArray(),0,("Package Name:" ).length());
			logDocument.endElement("","","H3");
			logDocument.startElement("","","packagename4log",attsOut);
			logDocument.characters(atts.getValue("Name").toCharArray(),0,atts.getValue("Name").length());
			logDocument.endElement("","","packagename4log");
			logDocument.startElement("","","H3",attsOut);		
			logDocument.characters(("Version:" ).toCharArray(),0,("Version:" ).length());
			logDocument.endElement("","","H3");
			logDocument.startElement("","","Version",attsOut);
			logDocument.characters(atts.getValue("Version").toCharArray(),0,atts.getValue("Version").length());
			logDocument.endElement("","","Version");
			logDocument.startElement("","","H3",attsOut);		
			logDocument.characters(("Package Install Date:" ).toCharArray(),0,("Package Install Date:" ).length());
			logDocument.endElement("","","H3");
			logDocument.startElement("","","installDate",attsOut);
			logDocument.characters(logDate.toCharArray(),0,logDate.length());
			logDocument.endElement("","","installDate");
			logDocument.startElement("","","H3",attsOut);		
			logDocument.characters(("Min. Compiere Version:" ).toCharArray(),0,("Min. Compiere Version:" ).length());
			logDocument.endElement("","","H3");
			logDocument.startElement("","","CompVer",attsOut);
			logDocument.characters(atts.getValue("CompVer").toCharArray(),0,atts.getValue("CompVer").length());
			logDocument.endElement("","","CompVer");
			logDocument.startElement("","","H3",attsOut);		
			logDocument.characters(("Min. Database Date:" ).toCharArray(),0,("Min. Database Date:" ).length());
			logDocument.endElement("","","H3");
			logDocument.startElement("","","DataBase",attsOut);
			logDocument.characters(atts.getValue("DataBase").toCharArray(),0,atts.getValue("DataBase").length());
			logDocument.endElement("","","DataBase");
			
			createImp_Sum_table ("AD_Package_Imp_Backup");
			createImp_Sum_table ("AD_Package_Imp");
			createImp_Sum_table ("AD_Package_Imp_Inst");
			createImp_Sum_table ("AD_Package_Imp_Detail");
			
			// Update Summary Package History Table
			String sql2 = "SELECT AD_PACKAGE_IMP_INST_ID FROM AD_PACKAGE_IMP_INST WHERE NAME ="
				+	"'" +  atts.getValue("Name")
				+	"' AND PK_VERSION ='" +  atts.getValue("Version") + "'";		
			int PK_preInstalled = DB.getSQLValue(trxName,sql2); 
			
			AD_Package_Imp_ID = DB.getNextID (Env.getAD_Client_ID(m_ctx), "AD_Package_Imp", null);
			
			StringBuffer sqlB = new StringBuffer ("INSERT INTO AD_Package_Imp") 
					.append( "(AD_Client_ID, AD_Org_ID, CreatedBy, UpdatedBy, " ) 
					.append( "AD_PACKAGE_IMP_ID, RELEASENO, PK_VERSION, VERSION " ) 
					.append( ", DESCRIPTION, NAME, CREATOR" ) 
					.append( ", CREATORCONTACT, CREATEDDATE,UPDATEDDATE,PK_STATUS)" )
					.append( "VALUES(" )
					.append( " "+ Env.getAD_Client_ID(m_ctx) )
					.append( ", "+ Env.getAD_Org_ID(m_ctx) )
					.append( ", "+ Env.getAD_User_ID(m_ctx) )
					.append( ", "+ Env.getAD_User_ID(m_ctx) )
					.append( ", " + AD_Package_Imp_ID ) 
					.append( ", '" + atts.getValue("CompVer") )
					.append( "', '" + atts.getValue("Version") )
					.append( "', '" + atts.getValue("DataBase") )
					.append( "', '" +  atts.getValue("Description").replaceAll("'","''"))
					.append( "', '" +  atts.getValue("Name") )
					.append( "', '" + atts.getValue("creator") )
					.append( "', '" + atts.getValue("creatorcontact") )
					.append( "', '" + atts.getValue("createddate") )
					.append( "', '" + atts.getValue("updateddate") )
					.append( "', '" + PK_Status )
					.append( "')" );
			Env.getAD_User_ID(m_ctx);
			int no = DB.executeUpdate (sqlB.toString(), trxName);		
			if (no == -1)
				log.info("Insert to Package import failed");
			
			if ( PK_preInstalled == -1){		
				AD_Package_Imp_Inst_ID = DB.getNextID (Env.getAD_Client_ID(m_ctx), "AD_Package_Imp_Inst", null);
				
				//Insert Package into package install log
				sqlB = new StringBuffer ("INSERT INTO AD_Package_Imp_Inst") 
						.append( "(AD_Client_ID, AD_Org_ID, CreatedBy, UpdatedBy, " ) 
						.append( "AD_PACKAGE_IMP_INST_ID, RELEASENO, PK_VERSION, VERSION " ) 
						.append( ", DESCRIPTION, NAME, CREATOR" ) 
						.append( ", CREATORCONTACT, CREATEDDATE,UPDATEDDATE,PK_STATUS)" )
						.append( "VALUES(" )
						.append( " "+ Env.getAD_Client_ID(m_ctx) )
						.append( ", "+ Env.getAD_Org_ID(m_ctx) )
						.append( ", "+ Env.getAD_User_ID(m_ctx) )
						.append( ", "+ Env.getAD_User_ID(m_ctx) )
						.append( ", " + AD_Package_Imp_Inst_ID ) 
						.append( ", '" + atts.getValue("CompVer") )
						.append( "', '" + atts.getValue("Version") )
						.append( "', '" + atts.getValue("DataBase") )
						.append( "', '" +  atts.getValue("Description").replaceAll("'","''"))
						.append( "', '" +  atts.getValue("Name") )
						.append( "', '" + atts.getValue("creator") )
						.append( "', '" + atts.getValue("creatorcontact") )
						.append( "', '" + atts.getValue("createddate") )
						.append( "', '" + atts.getValue("updateddate") )
						.append( "', '" + PK_Status )
						.append( "')" );
				
				Env.getAD_User_ID(m_ctx);
				no = DB.executeUpdate (sqlB.toString(), trxName);		
				if (no == -1)
					log.info("Insert to Package List import failed");
			}
			else{
				//Update package list with package status
				AD_Package_Imp_Inst_ID = PK_preInstalled;
				sqlB = new StringBuffer ("UPDATE AD_Package_Imp_Inst "
						+ "SET PK_Status = '" + PK_Status 
						+ "' WHERE AD_Package_Imp_Inst_ID = "+AD_Package_Imp_Inst_ID);		
				no = DB.executeUpdate (sqlB.toString(), trxName);
				if (no == -1)
					log.info("Update to package summary failed");
			}
			Env.setContext(m_ctx, "AD_Package_Imp_ID", AD_Package_Imp_ID);
			Env.setContext(m_ctx, "UpdateMode", m_UpdateMode);
			Env.setContext(m_ctx, "TrxName", trxName);
			Env.setContext(m_ctx, "PackageDirectory", packageDirectory);
			m_ctx.put("LogDocument", logDocument);
			m_ctx.put("PackInProcess", packIn);
		}
		else if (elementValue.equals("menu")) {
			//defer
			Element e = new Element(uri, localName, qName, new AttributesImpl(atts));
			if (stack.size() > 0)
				e.parent = stack.peek();
			stack.push(e);
			menus.add(e);
		}
		else {
			Element e = new Element(uri, localName, qName, new AttributesImpl(atts));
			if (stack.size() > 0)
				e.parent = stack.peek();
			stack.push(e);
			if (elementValue.equals("workflow"))
			{
				workflow.add(e);
			}
			
			if (elementValue.equals("workflowNode"))
			{
				nodes.add(e);
			}
			
			if(elementValue.equals(GenericPOHandler.Column_TAG_Name)) {
				columns.add(e);
			}
			//	for generic handler
			ElementHandler handler = handlers.get(elementValue);
			if(handler == null
					&& elementValue.startsWith(GenericPOHandler.TAG_Name)) {
				handler = new GenericPOHandler();
			}
			if (handler != null)
				handler.startElement(m_ctx, e);
			if (e.defer) {
				defer.add(new DeferEntry(e, true));
			}
		}	
	}   // startElement
    
	/**
     *	Check if Package History Table exists in database.  If not create
     *
     *      @param tablename
     *       	
     */
    public void createImp_Sum_table (String tablename){
    	// Check if table exists.
    	
    	Connection conn = DB.getConnectionRW();
    	DatabaseMetaData dbm;
    	try {
    		dbm = conn.getMetaData();
    		//    	 check if table is there
    		ResultSet tables = null;
    		if (m_DatabaseType.equals("Oracle"))
    			tables = dbm.getTables(null, null, tablename.toUpperCase(), null );
    		else if (m_DatabaseType.equals("PostgreSQL"))
    			tables = dbm.getTables(null, null, tablename.toLowerCase(), null );
    		
    		if (tables.next()) {
    			log.info ("Table Found");
    		}
    		else {        		
    			if (tablename.equals("AD_Package_Imp")){
    				StringBuffer sqlB = new StringBuffer ("CREATE TABLE "+ tablename.toUpperCase() + "( ")
    						.append( tablename.toUpperCase()+"_ID   NUMBER(10) NOT NULL, " )
    						.append( "AD_CLIENT_ID NUMBER(10) NOT NULL, " )
    						.append( "AD_ORG_ID  NUMBER(10) NOT NULL, " )
    						.append( "ISACTIVE CHAR(1) DEFAULT 'Y' NOT NULL, " )
    						.append( "CREATED DATE DEFAULT SYSDATE NOT NULL, " )
    						.append( "CREATEDBY NUMBER(10) NOT NULL, " )
    						.append( "UPDATED DATE DEFAULT SYSDATE NOT NULL, " )
    						.append( "UPDATEDBY NUMBER(10) NOT NULL, " )
    						.append( "NAME NVARCHAR2(60) NOT NULL, " )
    						.append( "PK_STATUS NVARCHAR2(22), " )
    						.append( "RELEASENO NVARCHAR2(20), " )
    						.append( "PK_VERSION NVARCHAR2(20), " ) 
    						.append( "VERSION NVARCHAR2(20), " )
    						.append( "DESCRIPTION NVARCHAR2(1000) NOT NULL, " ) 
    						.append( "EMAIL NVARCHAR2(60), " )
    						.append( "PROCESSED CHAR(1) DEFAULT 'N', " )
    						.append( "PROCESSING CHAR(1) DEFAULT 'N', " )
    						.append( "CREATOR VARCHAR2(60 ), " ) 
    						.append( "CREATORCONTACT VARCHAR2(255), " ) 
    						.append( " CREATEDDATE  VARCHAR2(25), " ) 
    						.append( "UPDATEDDATE VARCHAR2(25), " )					 
    						.append( "PRIMARY KEY( "+tablename.toUpperCase() +"_ID)"+")" );        		
    				
    				try {
    					PreparedStatement pstmt = DB.prepareStatement(sqlB.toString(),ResultSet.TYPE_FORWARD_ONLY,
    							ResultSet.CONCUR_UPDATABLE, null);
    					pstmt.executeUpdate();
    					MSequence.createTableSequence (m_ctx, "AD_Package_Imp", trxName);
    					pstmt.close();
    					pstmt = null;
    				}
    				catch (Exception e) {
    					log.info ("createImp_Sum_table:"+e);
    				}
    			}
    			if (tablename.equals("AD_Package_Imp_Inst")){
    				StringBuffer sqlB = new StringBuffer ("CREATE TABLE "+ tablename.toUpperCase() + "( ")
    						.append( tablename.toUpperCase()+"_ID   NUMBER(10) NOT NULL, " )
    						.append( "AD_CLIENT_ID NUMBER(10) NOT NULL, " )
    						.append( "AD_ORG_ID  NUMBER(10) NOT NULL, " )
    						.append( "ISACTIVE CHAR(1) DEFAULT 'Y' NOT NULL, " )
    						.append( "CREATED DATE DEFAULT SYSDATE NOT NULL, " )
    						.append( "CREATEDBY NUMBER(10) NOT NULL, " )
    						.append( "UPDATED DATE DEFAULT SYSDATE NOT NULL, " )
    						.append( "UPDATEDBY NUMBER(10) NOT NULL, " )
    						.append( "NAME NVARCHAR2(60) NOT NULL, " )
    						.append( "PK_STATUS NVARCHAR2(22), " )
    						.append( "RELEASENO NVARCHAR2(20), " )
    						.append( "PK_VERSION NVARCHAR2(20), " ) 
    						.append( "VERSION NVARCHAR2(20), " )
    						.append( "DESCRIPTION NVARCHAR2(1000) NOT NULL, " ) 
    						.append( "EMAIL NVARCHAR2(60), " ) 
    						.append( "PROCESSED CHAR(1) DEFAULT 'N', " )
    						.append( "PROCESSING CHAR(1) DEFAULT 'N', " )
    						.append( "CREATOR VARCHAR2(60 ), " ) 
    						.append( "CREATORCONTACT VARCHAR2(255), " ) 
    						.append( " CREATEDDATE  VARCHAR2(25), " ) 
    						.append( "UPDATEDDATE VARCHAR2(25), " )					 
    						.append( "PRIMARY KEY( "+tablename.toUpperCase() +"_ID)"+")" );        		
    				
    				try {
    					PreparedStatement pstmt = DB.prepareStatement(sqlB.toString(),ResultSet.TYPE_FORWARD_ONLY,
    							ResultSet.CONCUR_UPDATABLE, null);
    					pstmt.executeUpdate();
    					MSequence.createTableSequence (m_ctx, "AD_Package_Imp_Inst", trxName);
    					pstmt.close();
    					pstmt = null;
    				}
    				catch (Exception e) {
    					log.info ("createImp_Sum_table:"+e);
    				}
    			}
    			if (tablename.equals("AD_Package_Imp_Detail")){
    				StringBuffer sqlB = new StringBuffer ("CREATE TABLE "+ tablename.toUpperCase() + "( ")
    						.append( tablename.toUpperCase()+"_ID   NUMBER(10) NOT NULL, " )
    						.append( "AD_CLIENT_ID NUMBER(10) NOT NULL, " )
    						.append( "AD_ORG_ID  NUMBER(10) NOT NULL, " )
    						.append( "ISACTIVE CHAR(1) DEFAULT 'Y' NOT NULL, " )
    						.append( "CREATED DATE DEFAULT SYSDATE NOT NULL, " )
    						.append( "CREATEDBY NUMBER(10) NOT NULL, " )
    						.append( "UPDATED DATE DEFAULT SYSDATE NOT NULL, " )
    						.append( "UPDATEDBY NUMBER(10) NOT NULL, " )
    						.append( "NAME NVARCHAR2(60), " )
    						.append( "AD_PACKAGE_IMP_ID Number(10) NOT NULL, " )  
    						.append( "AD_ORIGINAL_ID Number(10) NOT NULL, " )
    						.append( "AD_BACKUP_ID Number(10), " )
    						.append( "ACTION NVARCHAR2(20), " ) 
    						.append( "SUCCESS NVARCHAR2(20), " )
    						.append( "TYPE NVARCHAR2(60), " ) 
    						.append( "TABLENAME NVARCHAR2(60), " )
    						.append( "AD_TABLE_ID NUMBER(10), " )
    						.append( "UNINSTALL CHAR(1), " )
    						.append( "PRIMARY KEY( "+tablename.toUpperCase() +"_ID)"+")" );        		
    				
    				try {
    					PreparedStatement pstmt = DB.prepareStatement(sqlB.toString(),ResultSet.TYPE_FORWARD_ONLY,
    							ResultSet.CONCUR_UPDATABLE, null);
    					pstmt.executeUpdate();
    					MSequence.createTableSequence (m_ctx, "AD_Package_Imp_Detail", trxName);
    					pstmt.close();
    					pstmt = null;
    				}
    				catch (Exception e) {
    					log.info ("createImp_Sum_table:"+e);
    				}
    			}
    			if (tablename.equals("AD_Package_Imp_Backup")){
    				StringBuffer sqlB = new StringBuffer ("CREATE TABLE "+ tablename.toUpperCase() + "( ")
    						.append( tablename.toUpperCase()+"_ID NUMBER(10) NOT NULL, " )
    						.append( "AD_CLIENT_ID NUMBER(10) NOT NULL, " )
    						.append( "AD_ORG_ID  NUMBER(10) NOT NULL, " )
    						.append( "ISACTIVE CHAR(1) DEFAULT 'Y' NOT NULL, " )
    						.append( "CREATED DATE DEFAULT SYSDATE NOT NULL, " )
    						.append( "CREATEDBY NUMBER(10) NOT NULL, " )
    						.append( "UPDATED DATE DEFAULT SYSDATE NOT NULL, " )
    						.append( "UPDATEDBY NUMBER(10) NOT NULL, " )        				 
    						.append( "AD_PACKAGE_IMP_ID Number(10) NOT NULL, " )
    						.append( "AD_PACKAGE_IMP_DETAIL_ID Number(10) NOT NULL, " )    					 
    						.append( "AD_TABLE_ID NUMBER(10), " )
    						.append( "AD_COLUMN_ID NUMBER(10), " )
    						.append( "AD_REFERENCE_ID NUMBER(10), " )
    						.append( "AD_PACKAGE_IMP_BCK_DIR NVARCHAR2(255), " )
    						.append( "AD_PACKAGE_IMP_ORG_DIR NVARCHAR2(255), " )
    						.append( "COLVALUE NVARCHAR2(2000), " )
    						.append( "UNINSTALL CHAR(1), " )
    						.append( "PRIMARY KEY( "+tablename.toUpperCase() +"_ID)"+")" );        	
    				
    				try {
    					PreparedStatement pstmt = DB.prepareStatement(sqlB.toString(),ResultSet.TYPE_FORWARD_ONLY,
    							ResultSet.CONCUR_UPDATABLE, null);
    					pstmt.executeUpdate();
    					MSequence.createTableSequence (m_ctx, "AD_Package_Imp_Backup", trxName);
    					pstmt.close();
    					pstmt = null;
    				}	
    				catch (Exception e) {
    					log.info ("createImp_Sum_table:"+e);
    				}
    			}	
    		}
    		
    		tables.close();
    	}
    	
    	catch (SQLException e) {
    		log.info ("createImp_Sum_table:"+e);
    	}
    	
    	finally
    	{
    		if( conn != null )
    		{
    			try
    			{
    				conn.close();
    			}
    			catch( Exception e ){}
    		}
    	}
    }

    /**
     *	Receive notification of the end of an element.
     * 	@param uri namespace
     * 	@param localName simple name
     * 	@param qName qualified name
     * 	@throws SAXException
     */
    public void endElement (String uri, String localName, String qName) throws SAXException {
    	// Check namespace.
    	String elementValue = null;
    	if ("".equals (uri))
    		elementValue = qName;
    	else
    		elementValue = uri + localName;
    	
    	if (elementValue.equals("adempiereAD")){
    		processDeferElements();
    		processMenuElements();
    		if (!PK_Status.equals("Completed with errors"))
    			PK_Status = "Completed successfully";
    		
    		//Update package history log with package status
    		StringBuffer sqlB = new StringBuffer ("UPDATE AD_Package_Imp "
    				+ "SET PK_Status = '" + PK_Status
    				+ "' WHERE AD_Package_Imp_ID = " + AD_Package_Imp_ID);		
    		int no = DB.executeUpdate (sqlB.toString(), trxName);
    		if (no == -1)
    			log.info("Update to package summary failed");
    		
    		//Update package list with package status		
    		sqlB = new StringBuffer ("UPDATE AD_Package_Imp_Inst "
    				+ "SET PK_Status = '" + PK_Status
    				+ "' WHERE AD_Package_Imp_Inst_ID = " + AD_Package_Imp_Inst_ID);		
    		no = DB.executeUpdate (sqlB.toString(), trxName);
    		if (no == -1)
    			log.info("Update to package list failed");
    		
        	if(workflow.size() > 0)
        	{
        		for (Element e : workflow)
        		{	
        			Attributes atts = e.attributes;
        			String workflowUuid = atts.getValue(AttributeFiller.getUUIDAttribute(I_AD_Workflow.Table_Name));
        			MWorkflow workflow = null;
    				int workflowId = IDFinder.getIdFromUUID(Env.getCtx(), I_AD_Workflow.Table_Name, workflowUuid, clientId, trxName);
    				if(workflowId > 0) {
    					workflow = new MWorkflow(m_ctx, workflowId , trxName);
    					int node_id = 0;
    					
    					String workFlowNodeName = AttributeFiller.getUUIDAttribute(I_AD_Workflow.COLUMNNAME_AD_WF_Node_ID);
    					if (workFlowNodeName != null && workFlowNodeName.trim().length() > 0)  {
    						MWFNode[] nodes = workflow.getNodes(false, clientId);
    						for (MWFNode node : nodes) {
    							if (node.getName().trim().equals(workFlowNodeName.trim())) {
    								node_id = node.getAD_WF_Node_ID();
    								workflow.setAD_WF_Node_ID(node_id);
    								if (!workflow.save())
    									System.out.println("Can not save Start Node "+ workFlowNodeName +"to Workflow " + workflowUuid +  " do not exist ");
    							    break;
    							}	
    						}
    						
    						if(node_id == 0) {
    							System.out.println("Unresolved: Start Node to Workflow " + workflowUuid +  " do not exist ");	
    						}
    						else
    						break;	
    					}
    					
    				}
        		}
        	}
        	//	
        	if(nodes.size() > 0) {
        		for (Element e : nodes) {
    	    		Attributes atts = e.attributes;
    	    		String nodeUuid = atts.getValue(AttributeFiller.getUUIDAttribute(I_AD_WF_Node.Table_Name));
    	    		MWFNode node = null;
    	    		int id = IDFinder.getIdFromUUID(Env.getCtx(), I_AD_WF_Node.Table_Name, nodeUuid, clientId, trxName);
    				if(id > 0) {
    					node = new MWFNode(m_ctx, id , trxName);
    					String workflowNodeUuid = atts.getValue(AttributeFiller.getUUIDAttribute(I_AD_WF_Node.COLUMNNAME_Workflow_ID));
    					if (!Util.isEmpty(workflowNodeUuid))  {
    						int workflowId = IDFinder.getIdFromUUID(Env.getCtx(), I_AD_Workflow.Table_Name, workflowNodeUuid, clientId, trxName);	
    						if (workflowId > 0) {
    							node.setWorkflow_ID(workflowId);
    							if(!node.save()) {
    								System.out.println("can not save Workflow " + workflowNodeUuid );
    							}
    						} else {
    							System.out.println("Unresolved: Workflow " + workflowNodeUuid +  " do not exist ");
    						}
    					}
    						
    				}
        		}
        	}
        	
        	//	Columns
        	if(columns.size() > 0) {
        		for (Element e : columns) {
    	    		Attributes atts = e.attributes;
    	    		String columnUuid = atts.getValue(AttributeFiller.getUUIDAttribute(I_AD_Column.Table_Name));
    	    		int id = IDFinder.getIdFromUUID(m_ctx, I_AD_Column.Table_Name, columnUuid, 0, trxName);
    				if(id > 0) {
    					MColumn column = new MColumn(m_ctx, id, trxName);
    					if(column.getAD_Table_ID() > 0) {
    						column.syncDatabase();
    					}
    				}
        		}
        	}
    		
    		logDocument.endElement("","","adempiereDocument");
    		logDocument.endDocument();	
    		try {
    			fw_document.close();
    		}
    		catch (Exception e)
    		{}
    		
    		//reset
    		setupHandlers();
    	} else {
    		Element e = stack.pop();
    		if (e.defer) {
    			defer.add(new DeferEntry(e, false));
    		} else {
	    		ElementHandler handler = handlers.get(elementValue);
	    		if (handler != null)
	    			handler.endElement(m_ctx, e);
	    		if (e.defer || e.deferEnd)
					defer.add(new DeferEntry(e, false));
	    		else if (!e.skip) {
	    			if (log.isLoggable(Level.INFO))
	    				log.info("Processed: " + e.getElementValue() + " - " + e.attributes.getValue(0));
	    		}
    		}
    	}
    	

    	
    }   // endElement
    
    private void processMenuElements() throws SAXException {
    	ElementHandler handler = handlers.get("menu");
		if (menus.size() > 0 && handler != null) {
			for (Element e : menus) {
				handler.startElement(m_ctx, e);
				handler.endElement(m_ctx, e);
			}
		}
	}
    
    private void processDeferElements() throws SAXException {
    	if (defer.isEmpty()) return;
    	
    	do {
    		int startSize = defer.size();
    		List<DeferEntry> tmp = new ArrayList<DeferEntry>(defer);
    		defer.clear();
    		for (DeferEntry d : tmp) {
    			if (d.startElement) {
	    			d.element.defer = false;
	    			d.element.unresolved = "";
	    			d.element.pass++;
    			} else {    				
    				if (d.element.deferEnd) {
    					d.element.deferEnd = false;
    	    			d.element.unresolved = "";
    				}    				
    			}
    			if (log.isLoggable(Level.INFO)) {
    				log.info("Processing Defer Element: " + d.element.getElementValue() + " - "
						+ d.element.attributes.getValue(0));
    			}
    			ElementHandler handler = handlers.get(d.element.getElementValue());
    			if (handler != null) {
    				if (d.startElement)
    					handler.startElement(m_ctx, d.element);
    				else
    					handler.endElement(m_ctx, d.element);
    			}
    			if (d.element.defer)
    				defer.add(d);
    			else if (!d.startElement) {
    				if (d.element.deferEnd)
    					defer.add(d);
    				else {
	    				if (log.isLoggable(Level.INFO))
	    					log.info("Imported Defer Element: " + d.element.getElementValue() + " - " 
	    							+ d.element.attributes.getValue(0));
    				}
    			}
    		}
    		int endSize = defer.size();
    		if (startSize == endSize) break;
    	} while (defer.size() > 0);
    	
    	if (defer.size() > 0) {
    		int count = 0;
    		for (DeferEntry d : defer) {
    			if (!d.startElement) {
    				count++;
    				if (log.isLoggable(Level.SEVERE))
    					log.severe("Unresolved: " + d.element.getElementValue() + " - " + d.element.attributes.getValue(0) + ", " + d.element.unresolved);
    			}
    		}
    		throw new RuntimeException("Failed to resolve dependency for " + count + " elements.");
    		//System.err.println("Failed to resolve dependency for " + count + " elements.");
    	}
    }

	// globalqss - add support for trx in 3.1.2
	public void set_TrxName(String trxName) {
		this.trxName = trxName;
	}
    
    // globalqss - add support for trx in 3.1.2
	public void setCtx(Properties ctx) {
		m_ctx = ctx;
	}

	class DeferEntry {
		Element element;
		boolean startElement = false;
		
		DeferEntry(Element e, boolean b) {
			element = e;
			startElement = b;
		}
	}

	/**
	 * @param packIn
	 */
	public void setProcess(PackIn packIn) {
		this.packIn = packIn;
	}
}   // PackInHandler
