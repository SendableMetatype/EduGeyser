# EduGeyser Legal Deep Dive: All Relevant Microsoft Terms

## Document Inventory

This analysis covers every Microsoft legal document that could conceivably apply to EduGeyser — a Geyser fork enabling Minecraft Education Edition clients to connect to Java Edition servers. The documents are analyzed in order of relevance.

---

## 1. Minecraft EULA (minecraft.net/eula)

### Applicability to Education Edition

The very first substantive line of the Minecraft EULA states:

> This EULA applies to all Minecraft websites, software, experiences, and services ("Services"), **except for the Minecraft Shop and Minecraft Education, each of which have their own separate terms.**

This is the single most important sentence in the entire legal landscape. The Minecraft EULA **explicitly excludes** Education Edition from its scope. This means:

- The mod policy ("Using Mods" section) does not apply to Education Edition
- The content distribution restrictions do not apply to Education Edition
- The server hosting permissions (and restrictions) do not apply to Education Edition
- The community standards embedded in the EULA do not apply to Education Edition
- The Realms terms do not apply to Education Edition

The EULA further clarifies in the Account Terms section: "An exception here is Minecraft Education. Minecraft Education is provided through the **group agreement in place with the school or organization** that purchased Minecraft Education for your use, so please view your group's terms for your legal agreement."

### What the Minecraft EULA *would* say if it applied

For comparison and context, the Minecraft EULA's mod section grants broad permissions for Java Edition:

- Players may create Mods (original creations without substantial Mojang code)
- Mods can be freely distributed
- "Modded Versions" (Mod + game) cannot be distributed
- Mods belong to their creators
- Server hosting is explicitly permitted
- Server operators can charge for access
- Third-party tools must not appear "official"

The EULA also says: "we are quite relaxed about what you do - in fact we really encourage you to do cool stuff." This has historically set the tone for how Mojang treats the modding ecosystem.

### Relevance to EduGeyser

**The Minecraft EULA does not govern Education Edition at all.** Its permissive modding stance is useful as evidence of Mojang's general philosophy, but it creates no rights or restrictions for EduGeyser. The relevant terms are found elsewhere.

---

## 2. Minecraft Education EULA (education.minecraft.net/eula)

### The Terms

The Education EULA is remarkably thin compared to the main Minecraft EULA. According to the Minecraft Education support documentation, there are multiple versions depending on how the license was acquired:

- **Demo version EULA** (education.minecraft.net/en-us/eula-demo)
- **iOS App Store EULA** (education.minecraft.net/en-us/eula-demo-ios)
- **Windows EULA** (education.minecraft.net/en-us/eula-demo-win)
- **Volume licensing**: governed by Microsoft Product Terms

All versions share a common structure. Key provisions:

**Section 1 — Installation and Use Rights:**
- "You may install and use any number of copies of the software on your devices" (demo/Windows version)
- iOS version limits to one copy per device as per Apple's rules
- Allows third-party software components under their own terms

**Section 2 — Time-Sensitive Software:**
- The software may stop running on a date defined internally
- Users may receive periodic reminders
- Data may become inaccessible when the software stops running

**Section 3 — Feedback:**
- If you give Microsoft feedback, they get a free license to use it

**Section 4 — Scope of License (iOS version):**
- The software is licensed, not sold
- Cannot work around technical limitations
- Cannot reverse engineer, decompile, or disassemble "except and only to the extent that applicable law expressly permits"
- Cannot make more copies than allowed
- Cannot publish, rent, lease, sell, or lend
- Cannot transfer the software or agreement

**Section 5 — Data Collection:**
- The software may collect information about you and usage
- This data may be used to provide services and improve products

**Section 6 — License Grant Conditions:**
- You must have a valid Microsoft 365 Education subscription AND a Minecraft Education subscription
- Without a valid subscription: limited to 10 boot-ups
- With valid subscription: unlimited use

**Section 7-14:** Standard boilerplate (export restrictions, support services, updates, binding arbitration, termination, entire agreement, applicable law, disclaimers and liability limitations).

### What's NOT in the Education EULA

Critically, the Education EULA contains **none of the following**:

- No modding clause (no equivalent to the Minecraft EULA's "Using Mods" section)
- No server hosting clause
- No community standards or code of conduct
- No content distribution restrictions
- No multiplayer terms
- No third-party tools restrictions
- No mention of connecting to external servers
- No mention of resource packs, addons, or behavior packs
- No mention of the MESS API or dedicated server functionality
- No prohibition on connecting to non-Education servers
- No data interoperability restrictions

### The Reverse Engineering Clause

The only clause with potential friction is Section 4's prohibition on reverse engineering "except and only to the extent that applicable law expressly permits." This is the standard Microsoft software license clause — identical language appears in the Microsoft Services Agreement Section 8.b.ii and in virtually every Microsoft product license.

**This exception is significant.** In Switzerland (the relevant jurisdiction), Article 21 of the Swiss Federal Act on Copyright permits decompilation for interoperability purposes. The EU Software Directive (2009/24/EC), which Swiss law aligns with on this point, explicitly permits reverse engineering when it is necessary to achieve interoperability with independently created software.

### Relevance to EduGeyser

The Education EULA is the primary governing document for Education Edition software itself. Its scope is narrow: installation, time limits, subscription validation, feedback, and standard legal boilerplate. It does not address the modding, server hosting, or interoperability scenarios that EduGeyser creates. The reverse engineering exception provides statutory cover for the protocol analysis work done to build EduGeyser.

---

## 3. Microsoft Services Agreement (microsoft.com/servicesagreement)

### Applicability

The Microsoft Services Agreement (MSA) is Microsoft's umbrella consumer terms. It lists covered services at the end, and that list includes "Education.minecraft.net" and "Minecraft games." The MSA explicitly states it applies alongside the Minecraft EULA (Section 14.a.xi): "The Minecraft EULA supplements these Terms and applies to your use of Minecraft websites, services, software and games."

However, since the Minecraft EULA itself excludes Education Edition, the question is whether the MSA applies independently to Education Edition. The Minecraft EULA's Account Terms section directs Education users to "the group agreement in place with the school or organization that purchased Minecraft Education." This suggests Education Edition is primarily governed by the volume licensing agreement, not the MSA.

That said, the MSA may apply to the extent a Microsoft account is used. The MSA's Code of Conduct and Software License provisions are the broadest terms that could touch EduGeyser.

### Section 8 — Software License

This is the most relevant section. Key provisions:

**Section 8.a:** Grants right to install and use one copy of the software per device, for personal non-commercial use.

**Section 8.b:** "The software is licensed, not sold." The license does not give you any right to:

- **(i)** Circumvent technological protection measures in the software or Services
- **(ii)** Disassemble, decompile, decrypt, hack, emulate, exploit, or reverse engineer any software or other aspect of the Services "**except and only to the extent that the applicable copyright law expressly permits doing so**"
- **(iii)** Separate components for use on different devices
- **(iv)** Publish, copy, rent, lease, sell, export, distribute, or lend
- **(v)** Transfer the software or licenses
- **(vi)** Use in unauthorized ways that could interfere with others' use
- **(vii)** Enable access by unauthorized third-party applications on Microsoft-authorized devices

### Section 3 — Code of Conduct

The Code of Conduct prohibits illegal activity, child exploitation, spam/malware, inappropriate content, fraud, circumventing access restrictions, harmful activity, IP infringement, and privacy violations.

**Section 3.a.vi** is potentially relevant: "Don't circumvent any restrictions on access to, usage, or availability of the Services (e.g., attempting to 'jailbreak' an AI system or impermissible scraping)."

### Section 14.s — AI Services

Not relevant to EduGeyser but included for completeness. Prohibits reverse engineering AI models, extracting data, and training AI on service outputs.

### Analysis for EduGeyser

**Section 8.b.ii (reverse engineering):** Contains the same "except and only to the extent" exception as the Education EULA. Swiss/EU interoperability exceptions apply.

**Section 8.b.i (circumvention):** EduGeyser does not circumvent any technological protection measure. It authenticates using legitimate Microsoft 365 / Entra ID credentials through Microsoft's own MSAL library. The MESS API is used as designed. No encryption is broken, no DRM is bypassed, no access controls are defeated.

**Section 8.b.vii (unauthorized third-party applications):** This provision specifically mentions "Microsoft-authorized device (e.g., Xbox consoles, Microsoft Surface, etc.)" — it's aimed at hardware modding, not server-side software that a client connects to. A Java server is not a "Microsoft-authorized device."

**Section 3.a.vi (circumvention):** The examples given ("jailbreaking an AI system", "impermissible scraping") suggest this targets abuse of Microsoft's own services. EduGeyser doesn't circumvent access to any Microsoft service — it uses the MESS API and authentication endpoints exactly as designed. The "circumvention" is on the Geyser/Java side, translating protocols to allow interoperability.

---

## 4. Microsoft Product Terms / Volume Licensing

### Structure

For organizations purchasing Education licenses through volume licensing (EES, MCA, MPSA, etc.), the governing document is the Microsoft Product Terms. The Microsoft Product Terms portal lists Minecraft: Education Edition under "Other Online Services" and requires selecting a specific licensing program to view terms.

The Product Terms incorporate:

- Universal License Terms (apply to all products)
- Product-specific terms for Minecraft: Education Edition
- Privacy & Security Terms
- The program agreement itself (e.g., Microsoft Customer Agreement)

### Universal License Terms

These establish the standard framework: software is licensed not sold, limited to authorized users, subject to acceptable use policies, and governed by the chosen licensing program. They incorporate data protection obligations (particularly relevant for education where student data is involved).

### Education-Specific Context

The Education support FAQ states: "Can I use my Minecraft Education licenses to access a Minecraft Bedrock or Java server? Since each of these Minecraft editions use distinct licensing systems, it's not possible to login to a different edition with Minecraft Education licenses."

This FAQ is about client licensing — whether an Education license can be used to log into a different edition's client (Bedrock or Java). It does not address server-side connectivity. EduGeyser does not change which client players use; Education Edition players continue using their Education Edition client with their Education license. EduGeyser translates on the server side, which is outside the scope of this FAQ entry.

---

## 5. Minecraft Usage Guidelines (MCUsageGuidelines)

### Applicability

The Minecraft Usage Guidelines (formerly the "Commercial Usage Guidelines" and "Brand and Asset Guidelines," merged in August 2023) are referenced by the Minecraft EULA as supplementary permissions. Since the Minecraft EULA excludes Education Edition, the Usage Guidelines also technically don't apply.

### Content (for reference)

The Usage Guidelines cover:

- **Naming:** Don't use "Minecraft" as the primary/dominant name; include disclaimer that your product/service is not official
- **Servers:** May charge for access; must ensure content is suitable for all ages; can't give competitive advantages for purchases
- **Mods:** Original creations that don't contain substantial Mojang code; tools must not appear official
- **Content creation:** Videos, screenshots, streams allowed with ads
- **Events:** In-person events have specific branding requirements
- **Music:** Specific requirements for using Minecraft music

### Relevance to EduGeyser

Even though the MUG technically doesn't apply, EduGeyser already complies with its spirit:

- The name "EduGeyser" doesn't use "Minecraft" as the primary name
- The README includes a clear "not official" disclaimer
- EduGeyser is a server-side tool, not client-side modification
- It's a free, open-source project with no monetization
- It's designed for educational use

---

## 6. Minecraft Education Dedicated Server Terms

### Official Dedicated Server Feature

Microsoft launched official dedicated server support for Minecraft Education. The setup process requires:

- A Global Admin must enable dedicated servers for the tenant
- Servers are registered through the MESS (Minecraft Education Server Service) API
- Cross-tenant play requires explicit configuration by admins from all participating tenants
- Students connect through the Education client's built-in server list

### Key Observations

The dedicated server documentation and FAQ reveal several important facts:

1. **Microsoft explicitly supports self-hosted servers.** Schools can run servers on their own hardware or in any cloud environment (Azure, AWS, etc.).

2. **Cross-tenant play is a first-party feature.** Microsoft designed the system to allow multiple schools to connect to the same server.

3. **Server registration is via API.** The MESS API is a documented, public-facing interface with tooling guides and Jupyter notebooks provided by Microsoft.

4. **Third-party partners can be approved.** The FAQ mentions: "If a 3rd party partner is approved by the Education team at Mojang, we will add them to the list of approved partners."

5. **No terms restrict what server software runs behind the MESS registration.** The dedicated server binary is provided for convenience, but the MESS API doesn't require that the server behind the IP/port is actually running the official binary.

### Relevance to EduGeyser

Method B (Dedicated Server registration through MESS API) is the cleanest legal path. A Global Admin enables dedicated servers, registers the EduGeyser server's IP/port through the MESS API, and students connect through the built-in server list. The MESS API doesn't mandate what software runs at the registered address. This is functionally identical to how organizations use custom Bedrock dedicated server configurations already.

---

## 7. Xbox Community Standards

### Applicability

The Minecraft EULA references Xbox Community Standards as supplementary community rules. Since the Minecraft EULA excludes Education Edition, these don't directly apply. However, they represent Microsoft's values around online safety.

### Content (summary)

The standards emphasize: be inclusive, don't harm others, keep gaming safe for everyone, no hate speech, no exploitation, no harassment, content should be appropriate for all ages.

### Relevance to EduGeyser

EduGeyser is entirely consistent with these values. It's designed for educational use, teacher-controlled, and enables only the kind of content the teacher chooses to install on the Java server. No safety controls are bypassed — the teacher remains the content authority.

---

## 8. Comparative Analysis: What Geyser Itself Does

GeyserMC has operated since 2019, bridging Bedrock Edition clients to Java Edition servers. This involves:

- Protocol translation between Bedrock and Java protocols
- Authentication bridging (Xbox Live to Mojang/Microsoft auth)
- Real-time packet manipulation and codec conversion

Microsoft/Mojang has never taken legal action against GeyserMC despite it being one of the most widely used Minecraft tools. Geyser operates in the same legal gray area as EduGeyser — the same reverse engineering provisions, the same "except to the extent applicable law permits" exceptions, the same protocol interoperability work.

The key difference: EduGeyser bridges an Education client (with M365/Entra ID auth) to Java servers, while Geyser bridges a standard Bedrock client (with Xbox Live auth) to Java servers. The legal framework is identical. If anything, EduGeyser has a stronger interoperability argument because it serves an explicitly educational purpose.

---

## 9. Synthesis: Legal Risk Assessment

### What EduGeyser does, legally speaking

1. **Authentication:** Uses Microsoft's own MSAL library to obtain tokens. No credentials are intercepted. The token flow uses the same OAuth endpoints Microsoft designed for third-party applications. Method B uses the MESS API exactly as documented.

2. **Protocol Translation:** Geyser (the upstream project) handles all Bedrock-to-Java protocol translation. EduGeyser adds only the Education-specific delta: 3 extra strings in the StartGamePacket serializer, a `signedToken` claim in the handshake JWT, and 7 packet serializers changed from illegal to ignored.

3. **Server-Side Only:** EduGeyser modifies no client code. The Education client is unmodified. All translation happens on the server, which is the teacher's/school's infrastructure.

4. **Content Control:** The teacher decides what Java server software and plugins to run, the same way they decide what resource packs and behavior packs to install. Education Edition already supports custom content with no restrictions.

### Terms that arguably apply

| Document | Applies? | Key Provision | Risk Level |
|----------|----------|---------------|------------|
| Minecraft EULA | **No** — explicitly excludes Education | N/A | None |
| Education EULA | **Yes** — to the client software | Reverse engineering (with statutory exception) | Low |
| Microsoft Services Agreement | **Possibly** — via Microsoft account | Section 8.b.ii (reverse engineering with exception) | Low |
| Volume Licensing / Product Terms | **Yes** — to the organization | Standard enterprise terms | Low |
| Usage Guidelines | **No** — subsidiary to excluded EULA | N/A | None |
| Xbox Community Standards | **No** — subsidiary to excluded EULA | N/A | None |

### Risk factors (honestly assessed)

**Low risk:**
- The Education EULA and MSA both contain reverse engineering prohibitions, but both also contain the standard "except to the extent applicable law permits" exception
- Swiss and EU law explicitly permit reverse engineering for interoperability
- Geyser has operated for 5+ years without legal action
- Method B uses Microsoft's own MESS API as designed
- MSAL is Microsoft's own authentication library, designed for third-party use
- No DRM circumvention, no access control bypass, no encryption breaking
- Server-side only — no client modification

**Medium risk:**
- Microsoft could argue that EduGeyser "circumvents restrictions on access" (MSA 3.a.vi), framing the Education-to-Java bridge as unauthorized access
- Microsoft could argue the Education client is only licensed for use with Education/Bedrock servers
- Microsoft could update the Education EULA to explicitly prohibit connecting to non-Education servers
- The MESS API could be modified to validate server software

**Mitigating factors:**
- Microsoft provides no explicit prohibition on connecting Education clients to external servers
- The Education EULA is so thin it doesn't address servers at all
- Education Edition already allows unrestricted resource packs, behavior packs, and addons — including packs that add a full server list UI (VDX Desktop UI)
- Microsoft actively promotes third-party integrations in Education
- The educational purpose strengthens any fair use / interoperability argument
- Teachers already have full content control authority
- Microsoft has shown no interest in enforcing against interoperability tools

### Conclusion

EduGeyser operates in the same legal territory as GeyserMC itself — technically touching reverse engineering provisions that are subject to statutory interoperability exceptions. The Education EULA's silence on servers, mods, and external connections creates no prohibition. Microsoft's own dedicated server feature demonstrates that external server connections are an intended use case. The MESS API provides a clean, first-party path for server registration.

The strongest legal position is Method B (MESS API registration by a Global Admin), which uses every part of the system exactly as Microsoft designed it. The only novel element is that the server behind the registered IP happens to be running Geyser + a Java server instead of the official Bedrock dedicated server binary — but nothing in any Microsoft document requires a specific server binary at the registered address.

---

## Appendix A: Document URLs

- Minecraft EULA: https://www.minecraft.net/en-us/eula
- Minecraft Education EULA: https://education.minecraft.net/en-us/eula
- Minecraft Education EULA (Demo): https://education.minecraft.net/en-us/eula-demo
- Minecraft Education EULA (iOS): https://education.minecraft.net/en-us/eula-demo-ios
- Minecraft Education EULA (Windows): https://education.minecraft.net/en-us/eula-demo-win
- Microsoft Services Agreement: https://www.microsoft.com/servicesagreement
- Minecraft Usage Guidelines: https://aka.ms/MCUsageGuidelines
- Microsoft Product Terms (Education Edition): https://www.microsoft.com/licensing/terms/productoffering/MinecraftEducationEdition
- Education EULA Reference (Support): https://edusupport.minecraft.net/hc/en-us/articles/4405348643092
- Dedicated Server FAQ: https://edusupport.minecraft.net/hc/en-us/articles/41758309283348
- Dedicated Server Tooling Guide: https://edusupport.minecraft.net/hc/en-us/articles/41757415076884

## Appendix B: Key Quotes from Microsoft Documents

**Minecraft EULA — Exclusion:**
"This EULA applies to all Minecraft websites, software, experiences, and services ('Services'), except for the Minecraft Shop and Minecraft Education, each of which have their own separate terms."

**Minecraft EULA — Account Terms:**
"Minecraft Education is provided through the group agreement in place with the school or organization that purchased Minecraft Education for your use."

**Education EULA — Reverse Engineering:**
"[You may not] reverse engineer, decompile, or disassemble the software, except and only to the extent that applicable law expressly permits."

**MSA Section 8.b.ii:**
"[You may not] disassemble, decompile, decrypt, hack, emulate, exploit, or reverse engineer any software or other aspect of the Services that is included in or accessible through the Services, except and only to the extent that the applicable copyright law expressly permits doing so."

**Education FAQ — Server Compatibility:**
"Since each of these Minecraft editions use distinct licensing systems, it's not possible to login to a different edition with Minecraft Education licenses."

**Dedicated Server FAQ — Third Parties:**
"If a 3rd party partner is approved by the Education team at Mojang, we will add them to the list of approved partners to which Global Admins can grant access."

---

*Analysis prepared March 2026. All documents retrieved and verified from primary sources. This is a factual analysis, not legal advice.*
