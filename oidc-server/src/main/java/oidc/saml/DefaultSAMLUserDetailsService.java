package oidc.saml;

import oidc.model.FederatedUserInfo;
import oidc.service.HashedPairwiseIdentifierService;
import oidc.user.FederatedUserInfoService;
import org.opensaml.saml2.core.Attribute;
import org.opensaml.saml2.core.AuthenticatingAuthority;
import org.opensaml.saml2.core.AuthnStatement;
import org.opensaml.saml2.core.NameID;
import org.opensaml.xml.XMLObject;
import org.opensaml.xml.schema.XSAny;
import org.opensaml.xml.schema.XSString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.saml.SAMLCredential;
import org.springframework.security.saml.userdetails.SAMLUserDetailsService;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;

public class DefaultSAMLUserDetailsService implements SAMLUserDetailsService {

  public static final String PERSISTENT_NAME_ID_FORMAT = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";
  public static final String EDU_PERSON_TARGETED_ID = "urn:mace:dir:attribute-def:eduPersonTargetedID";

  private static final Logger LOG = LoggerFactory.getLogger(DefaultSAMLUserDetailsService.class);

  private final String localSpEntityId;

  @Autowired
  private FederatedUserInfoService extendedUserInfoService;

  @Autowired
  private HashedPairwiseIdentifierService hashedPairwiseIdentifierService;

  public DefaultSAMLUserDetailsService(String localSpEntityId) {
    super();
    this.localSpEntityId = localSpEntityId;
  }

  @Override
  public Object loadUserBySAML(SAMLCredential credential) throws UsernameNotFoundException {
    String unspecifiedNameId = credential.getNameID().getValue();

    Map<String, List<String>> properties = getAttributes(credential);

    //we saved the clientId on the relay state in ProxySAMLEntryPoint#getProfileOptions
    String clientId = credential.getRelayState();
    List<String> persistentIds = properties.get(EDU_PERSON_TARGETED_ID);
    String sub;
    if (CollectionUtils.isEmpty(persistentIds)) {
      sub = hashedPairwiseIdentifierService.getIdentifier(unspecifiedNameId, clientId);
      LOG.info("Using the hashedPairwiseIdentifierService for the {} sub for {} and {}", sub, unspecifiedNameId, clientId);
    } else {
      sub = persistentIds.get(0);
      LOG.info("Using the persistent identifier for the {} sub for {} and {}", sub, unspecifiedNameId, clientId);
    }
    String authenticatingAuthority = getAuthenticatingAuthority(credential);

    FederatedUserInfo existingUserInfo = (FederatedUserInfo) extendedUserInfoService.getByUsernameAndClientId(sub, clientId);
    FederatedUserInfo userInfo = this.buildUserInfo(unspecifiedNameId, sub, authenticatingAuthority, properties);

    if (existingUserInfo == null) {
      extendedUserInfoService.saveUserInfo(userInfo);
    } else if (!existingUserInfo.hashed().equals(userInfo.hashed())) {
      userInfo.setId(existingUserInfo.getId());
      extendedUserInfoService.saveUserInfo(userInfo);
    }
    //if the sp-entity-id equals the OIDC server (e.g. non-proxy mode to access the GUI) we grant admin rights
    return new SAMLUser(sub, clientId.equals(this.localSpEntityId), clientId);
  }

  private Map<String, List<String>> getAttributes(SAMLCredential credential) {
    Map<String, List<String>> properties = new HashMap<>();

    List<Attribute> attributes = credential.getAttributes();
    for (Attribute attribute : attributes) {
      String name = attribute.getName();
      List<String> values = new ArrayList<String>();
      List<XMLObject> attributeValues = attribute.getAttributeValues();
      for (XMLObject xmlObject : attributeValues) {
        String value = getStringValueFromXMLObject(xmlObject);
        if (StringUtils.hasText(value)) {
          values.add(value);
        }
      }
      properties.put(name, values);
    }
    return properties;
  }

  private String getStringValueFromXMLObject(XMLObject xmlObj) {
    if (xmlObj instanceof XSString) {
      return ((XSString) xmlObj).getValue();
    } else if (xmlObj instanceof XSAny) {
      XSAny xsAny = (XSAny) xmlObj;
      String textContent = xsAny.getTextContent();
      if (StringUtils.hasText(textContent)) {
        return textContent;
      }
      List<XMLObject> unknownXMLObjects = xsAny.getUnknownXMLObjects();
      if (!CollectionUtils.isEmpty(unknownXMLObjects)) {
        XMLObject xmlObject = unknownXMLObjects.get(0);
        if (xmlObject instanceof NameID) {
          NameID nameID = (NameID) xmlObject;
          if (PERSISTENT_NAME_ID_FORMAT.equals(nameID.getFormat())) {
            return nameID.getValue();
          }
        }
      }
    }
    return null;
  }

  private String getAuthenticatingAuthority(SAMLCredential credential) {
    List<AuthnStatement> authnStatements = credential.getAuthenticationAssertion().getAuthnStatements();
    for (AuthnStatement authnStatement : authnStatements) {
      final List<AuthenticatingAuthority> authorities = authnStatement.getAuthnContext().getAuthenticatingAuthorities();
      for (AuthenticatingAuthority authenticatingAuthority : authorities) {
        String uri = authenticatingAuthority.getURI();
        if (StringUtils.hasText(uri)) {
          return uri;
        }
      }
    }
    throw new IllegalArgumentException("No AuthenticatingAuthority present in the Assertion, cannot determine IdP");
  }

  private FederatedUserInfo buildUserInfo(String unspecifiedNameId, String sub, String authenticatingAuthority, Map<String, List<String>> properties) {
    FederatedUserInfo userInfo = new FederatedUserInfo();

    userInfo.setUnspecifiedNameId(unspecifiedNameId);
    userInfo.setSub(sub);
    userInfo.setAuthenticatingAuthority(authenticatingAuthority);

    userInfo.setName(flatten(properties.get("urn:mace:dir:attribute-def:cn")));
    userInfo.setPreferredUsername(flatten(properties.get("urn:mace:dir:attribute-def:displayName")));
    userInfo.setNickname(flatten(properties.get("urn:mace:dir:attribute-def:displayName")));
    userInfo.setGivenName(flatten(properties.get("urn:mace:dir:attribute-def:givenName")));
    userInfo.setFamilyName(flatten(properties.get("urn:mace:dir:attribute-def:sn")));
    userInfo.setLocale(flatten(properties.get("urn:mace:dir:attribute-def:preferredLanguage")));
    userInfo.setEmail(flatten(properties.get("urn:mace:dir:attribute-def:mail")));

    userInfo.setSchacHomeOrganization(flatten(properties.get("urn:mace:terena.org:attribute-def:schacHomeOrganization")));
    userInfo.setSchacHomeOrganizationType(flatten(properties.get("urn:mace:terena.org:attribute-def:schacHomeOrganizationType")));

    userInfo.setEduPersonAffiliations(set(properties.get("urn:mace:dir:attribute-def:eduPersonAffiliation")));
    userInfo.setEduPersonScopedAffiliations(set(properties.get("urn:mace:dir:attribute-def:eduPersonScopedAffiliation")));

    userInfo.setIsMemberOfs(set(properties.get("urn:mace:dir:attribute-def:isMemberOf")));
    userInfo.setEduPersonEntitlements(set(properties.get("urn:mace:dir:attribute-def:eduPersonEntitlement")));
    userInfo.setSchacPersonalUniqueCodes(set(properties.get("urn:schac:attribute-def:schacPersonalUniqueCode")));
    userInfo.setEduPersonPrincipalName(flatten(properties.get("urn:mace:dir:attribute-def:eduPersonPrincipalName")));
    userInfo.setUids(set(properties.get("urn:mace:dir:attribute-def:uid")));
    userInfo.setEduPersonTargetedId(flatten(properties.get("urn:mace:dir:attribute-def:eduPersonTargetedID")));

    return userInfo;
  }

  private String flatten(List<String> values) {
    return CollectionUtils.isEmpty(values) ? null : values.get(0);
  }

  private Set<String> set(List<String> values) {
    return CollectionUtils.isEmpty(values) ? new HashSet<String>() : new HashSet<>(values);
  }

  public void setExtendedUserInfoService(FederatedUserInfoService extendedUserInfoService) {
    this.extendedUserInfoService = extendedUserInfoService;
  }

  public void setHashedPairwiseIdentifierService(HashedPairwiseIdentifierService hashedPairwiseIdentifierService) {
    this.hashedPairwiseIdentifierService = hashedPairwiseIdentifierService;
  }
}
