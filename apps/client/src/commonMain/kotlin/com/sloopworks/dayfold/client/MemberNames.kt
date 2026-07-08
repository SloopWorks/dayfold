package com.sloopworks.dayfold.client

// Render-time resolution of a checklist doneBy userId → a friendly name (ADR 0015 content-
// blind: doneBy lives in content; names come from the identity-layer roster). Pure + unit-
// tested. null return = unresolvable → the caller shows "a family member".
fun firstNameOf(name: String): String = name.trim().substringBefore(' ').trim()

fun displayNameFor(userId: String?, members: List<FamilyMember>, selfId: String?): String? {
  if (userId == null) return null
  if (userId == selfId) return "You"
  val name = members.firstOrNull { it.uid == userId }?.displayName ?: return null
  return name.trim().ifEmpty { return null }.let(::firstNameOf)
}
