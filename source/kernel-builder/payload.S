// SPDX-License-Identifier: GPL-2.0-only
// Cairn V1.0.1 kernel-side payload.
// Licensed separately from the GPL-3.0-only userspace build tool.
// This notice does not relicense Linux or third-party material.
	.section .text
	.text
	.balign	4
_start:
cairn_bprm:
	paciasp
cairn_bprm_b:
	b	0
cairn_sip:
	mov	x16, #0
	b	cairn_chk
cairn_avc:
	mov	x16, #1
	b	cairn_chk
cairn_avcn:
	mov	x16, #2
	b	cairn_chk
cairn_chk:
	mrs	x9, sp_el0
	ldr	x10, [x9, #2080]
	cbz	x10, cairn_chk_dn
	ldr	w14, [x10, #8]
	cbnz	w14, cairn_chk_dn
	autiasp
	mov	w0, wzr
	ret
cairn_chk_dn:
	adr	x15, cairn_sip_dn
	add	x15, x15, x16, lsl #3
	br	x15
cairn_sip_dn:
	sub	sp, sp, #0x50
cairn_sip_b:
	b	0
cairn_avc_dn:
	sub	sp, sp, #0x70
cairn_avc_b:
	b	0
cairn_avcn_dn:
	stp	x29, x30, [sp, #-0x50]!
cairn_avcn_b:
	b	0
cairn_prctl:
	stp	x29, x30, [sp, #-96]!
	stp	x28, x27, [sp, #16]
	stp	x26, x25, [sp, #32]
	stp	x24, x23, [sp, #48]
	stp	x22, x21, [sp, #64]
	stp	x20, x19, [sp, #80]
	mov	x29, sp
	sub	sp, sp, #0x1c0
	ldr	w19, [x0]
	mov	w8, #0x4952
	movk	w8, #0x4341, lsl #16
	cmp	w19, w8
	b.ne	cairn_prctl_orig
	ldp	x21, x22, [x0, #8]
	adr	x7, cairn_hash
	ldr	x8, [x7]
	cmp	x21, x8
	b.ne	cairn_prctl_bad
	ldr	x8, [x7, #8]
	cmp	x22, x8
	b.ne	cairn_prctl_bad

    ldr x23, [x0, #24]
    ldr x24, [x0, #32]
    cmp x23, #2
    b.eq cairn_wl_write
    cbnz x23, cairn_prctl_bad
cairn_pd:
	bl	0
	cbz	x0, cairn_prctl_bad
	mov	x14, x0
	str	wzr, [x14, #8]
	str	wzr, [x14, #12]
	str	wzr, [x14, #16]
	str	wzr, [x14, #20]
	str	wzr, [x14, #24]
	str	wzr, [x14, #28]
	str	wzr, [x14, #32]
	str	wzr, [x14, #36]
	movz	x8, #0xffff
	movk	x8, #0xffff, lsl #16
	movk	x8, #0x1ff, lsl #32
	str	x8, [x14, #48]
	str	x8, [x14, #56]
	str	x8, [x14, #64]
	movz	x9, #0x4, lsl #48
	orr	x8, x8, x9
	str	x8, [x14, #72]
	mov	x0, x14
cairn_cm:
	bl	0
	stp	x0, x1, [sp, #-16]!
	adr	x0, cairn_fmt_ok
cairn_ok_bl:
	bl	0
	ldp	x0, x1, [sp], #16
	mov	w0, #0
	b	cairn_prctl_out
cairn_prctl_bad:
	stp	x0, x1, [sp, #-16]!
	adr	x0, cairn_fmt_bad
cairn_bad_bl:
	bl	0
	ldp	x0, x1, [sp], #16
	mov	x0, #-1
cairn_prctl_out:
	add	sp, sp, #0x1c0
	ldp	x20, x19, [sp, #80]
	ldp	x22, x21, [sp, #64]
	ldp	x24, x23, [sp, #48]
	ldp	x26, x25, [sp, #32]
	ldp	x28, x27, [sp, #16]
	ldp	x29, x30, [sp], #96
	autiasp
	ret
cairn_prctl_orig:
cairn_prctl_tail:
	b	0
cairn_stat:
	mov	x12, x1
	cbz	x1, cairn_stat_orig
	cmn	x1, #0xfff
	b.cs	cairn_stat_orig
	ldr	x9, [x1]
	cbz	x9, cairn_stat_orig
	ldr	w10, [x9, #0]
	ldr	w11, cairn_scw0
	cmp	w10, w11
	b.ne	cairn_stat_orig
	ldr	w10, [x9, #4]
	ldr	w11, cairn_scw1
	cmp	w10, w11
	b.ne	cairn_stat_orig
	ldr	w10, [x9, #8]
	ldr	w11, cairn_scw2
	cmp	w10, w11
	b.ne	cairn_stat_orig
	ldr	w10, [x9, #12]
	mov	w11, #0xffffff
	and	w10, w10, w11
	ldr	w11, cairn_scn3
	cmp	w10, w11
	b.eq	cairn_stat_cnd
	ldr	w11, cairn_scw3
	cmp	w10, w11
	b.ne	cairn_stat_orig

    stp x0, x30, [sp, #-16]!
    bl cairn_wl_allowed
    mov w17, w0
    ldp x0, x30, [sp], #16
    cbz w17, cairn_stat_orig
	cbz	x3, cairn_stat_hit_nostat
	stp	x28, x27, [sp, #-32]!
	mov	x28, x3
	adr	x27, cairn_kstat_tbl
	ldp	x9, x10, [x27, #0]
	stp	x9, x10, [x28, #0]
	ldp	x9, x10, [x27, #16]
	stp	x9, x10, [x28, #16]
	ldp	x9, x10, [x27, #32]
	stp	x9, x10, [x28, #32]
	ldp	x9, x10, [x27, #48]
	stp	x9, x10, [x28, #48]
	ldr	x9, [x27, #64]
	str	x9, [x28, #64]
	ldp	x28, x27, [sp], #32
	stp	x0, x30, [sp, #-16]!
	adr	x0, cairn_fmt_stat
cairn_stat_bl:
	bl	0
	ldp	x0, x30, [sp], #16
cairn_stat_hit_nostat:
	mov	w0, wzr
	ret
cairn_stat_orig:
	paciasp
	sub	sp, sp, #0x70
	stp	x29, x30, [sp, #32]
	str	x25, [sp, #48]
	stp	x24, x23, [sp, #64]
	stp	x22, x21, [sp, #80]
	stp	x20, x19, [sp, #96]
	add	x29, sp, #0x20
	mov	x1, x12
cairn_stat_ret:
	b	0
	nop
cairn_stat_cnd:
	cbz	x3, cairn_stat_cnd_out
	stp	x28, x27, [sp, #-32]!
	mov	x28, x3
	adr	x27, cairn_kstat_tbl
	ldp	x9, x10, [x27, #0]
	stp	x9, x10, [x28, #0]
	ldp	x9, x10, [x27, #16]
	stp	x9, x10, [x28, #16]
	ldp	x9, x10, [x27, #32]
	stp	x9, x10, [x28, #32]
	ldp	x9, x10, [x27, #48]
	stp	x9, x10, [x28, #48]
	ldr	x9, [x27, #64]
	str	x9, [x28, #64]
	ldp	x28, x27, [sp], #32
	stp	x0, x30, [sp, #-16]!
	adr	x0, cairn_fmt_cndst
cairn_cndstat_bl:
	bl	0
	ldp	x0, x30, [sp], #16
cairn_stat_cnd_out:
	mov	w0, wzr
	ret
cairn_vread:
	stp	x0, x1, [sp, #-0x40]!
	stp	x2, x3, [sp, #16]
	stp	x29, x30, [sp, #32]
	mrs	x9, sp_el0
	ldr	w9, [x9, #1560]
	cmp	w9, #1
	b.ne	cairn_vr_miss
	ldr	x10, [x0, #176]
	cbz	x10, cairn_vr_miss
	adr	x13, cairn_vr_tbl
	mov	w16, #5
cairn_vr_lv:
	cbz	x10, cairn_vr_miss
	ldr	x11, [x10, #40]
	cbz	x11, cairn_vr_miss
	ldr	w14, [x13, #8]
	ldr	w15, [x10, #36]
	cmp	w14, w15
	b.ne	cairn_vr_miss
	mov	w17, #0
cairn_vr_bl:
	cmp	w17, w14
	b.ge	cairn_vr_nx
	ldrb	w9, [x11, x17]
	ldrb	w12, [x13, x17]
	cmp	w9, w12
	b.ne	cairn_vr_miss
	add	w17, w17, #1
	b	cairn_vr_bl
cairn_vr_nx:
	ldr	x10, [x10, #24]
	add	x13, x13, #16
	subs	w16, w16, #1
	b.ne	cairn_vr_lv
	ldr	x14, [sp, #24]
	cbz	x14, cairn_vr_rc
	ldr	x14, [x14]
	ldr	x15, [x0, #184]
	cbz	x15, cairn_vr_miss
	ldr	x15, [x15, #80]
	cbz	x15, cairn_vr_miss
	cmp	x14, x15
	b.lt	cairn_vr_rc
	b.gt	cairn_vr_miss
	adr	x0, cairn_fmt_vre
	b	cairn_vr_eof
	bl	0
	b	cairn_vr_miss
cairn_vr_rc:
	adr	x0, cairn_fmt_vrr
cairn_vr_blr:
	bl	0
	b	cairn_vr_miss
cairn_vr_eof:
	adr	x0, cairn_fmt_vre
cairn_vr_be:
	bl	0
	ldr	x0, [sp, #8]
	cbz	x0, cairn_vr_miss
	adr	x1, cairn_rc_add
	adr	x13, cairn_add_len
	ldr	w2, [x13]
cairn_vr_bc:
	bl	0
	cbnz	x0, cairn_vr_miss
	ldr	x3, [sp, #24]
	cbz	x3, cairn_vr_ret
	ldr	x4, [x3]
	adr	x13, cairn_add_len
	ldr	w5, [x13]
	add	x4, x4, x5
	str	x4, [x3]
cairn_vr_ret:
	ldp	x29, x30, [sp, #32]
	ldp	x2, x3, [sp, #16]
	ldp	x0, x1, [sp], #0x40
	adr	x13, cairn_add_len
	ldr	w0, [x13]
	autiasp
	ret
cairn_vr_miss:
	ldp	x29, x30, [sp, #32]
	ldp	x2, x3, [sp, #16]
	ldp	x0, x1, [sp], #0x40
	sub	sp, sp, #0xa0
	stp	x29, x30, [sp, #96]
	stp	x24, x23, [sp, #112]
	stp	x22, x21, [sp, #128]
	stp	x20, x19, [sp, #144]
	add	x29, sp, #0x60
cairn_vr_j:
	b	0
cairn_exec:
	ldr	x12, [x1]
	cbz	x12, cairn_exec_b
	mov	x14, x0
	ldr	w9, [x12, #0]
	ldr	w10, cairn_scn0
	cmp	w9, w10
	b.ne	cairn_cnd_miss
	ldr	w9, [x12, #4]
	ldr	w10, cairn_scn1
	cmp	w9, w10
	b.ne	cairn_cnd_miss
	ldr	w9, [x12, #8]
	ldr	w10, cairn_scn2
	cmp	w9, w10
	b.ne	cairn_cnd_miss
	ldr	w9, [x12, #12]
	ldr	w10, cairn_scn3
	cmp	w9, w10
	b.ne	cairn_cnd_miss
	adr	x8, cairn_cnd_dst
	ldr	w9, [x8, #0]
	str	w9, [x12, #0]
	ldr	w9, [x8, #4]
	str	w9, [x12, #4]
	ldr	w9, [x8, #8]
	str	w9, [x12, #8]
	ldrh	w9, [x8, #12]
	strh	w9, [x12, #12]
	b	cairn_exec_b
cairn_cnd_miss:
	ldr	w9, [x12, #0]
	ldr	w10, cairn_scw0
	cmp	w9, w10
	b.ne	cairn_exec_b
	ldr	w9, [x12, #4]
	ldr	w10, cairn_scw1
	cmp	w9, w10
	b.ne	cairn_exec_b
	ldr	w9, [x12, #8]
	ldr	w10, cairn_scw2
	cmp	w9, w10
	b.ne	cairn_exec_b
	ldr	w9, [x12, #12]
	mov	w11, #0xffffff
	and	w9, w9, w11
	ldr	w10, cairn_scw3
	cmp	w9, w10
	b.ne	cairn_exec_b

    stp x0, x30, [sp, #-16]!
    bl cairn_wl_allowed
    mov w17, w0
    ldp x0, x30, [sp], #16
    cbz w17, cairn_exec_b
	mrs	x9, sp_el0
	ldr	x10, [x9, #2080]
	cbz	x10, cairn_exec_b
	str	wzr, [x10, #8]
	str	wzr, [x10, #12]
	str	wzr, [x10, #16]
	str	wzr, [x10, #20]
	str	wzr, [x10, #24]
	str	wzr, [x10, #28]
	str	wzr, [x10, #32]
	str	wzr, [x10, #36]
	movz	x8, #0xffff
	movk	x8, #0xffff, lsl #16
	movk	x8, #0x1ff, lsl #32
	str	x8, [x10, #48]
	str	x8, [x10, #56]
	str	x8, [x10, #64]
	str	x8, [x10, #72]
	sub	sp, sp, #0xb0
	stp	x0, x1, [sp, #0]
	stp	x2, x3, [sp, #16]
	stp	x4, x5, [sp, #32]
	stp	x6, x7, [sp, #48]
	stp	x8, x9, [sp, #64]
	stp	x10, x11, [sp, #80]
	stp	x12, x13, [sp, #96]
	stp	x14, x15, [sp, #112]
	stp	x16, x17, [sp, #128]
	str	x18, [sp, #144]
	stp	x29, x30, [sp, #152]
	ldr	x1, [sp, #8]
	ldr	x1, [x1]
	adr	x0, cairn_fmt_exec
cairn_exec_log:
	bl	0
	ldr	x1, [sp, #8]
	ldr	x12, [x1]
	adr	x8, cairn_cnd_path
	ldr	w9, [x8, #0]
	str	w9, [x12, #0]
	ldr	w9, [x8, #4]
	str	w9, [x12, #4]
	ldr	w9, [x8, #8]
	str	w9, [x12, #8]
	ldrh	w9, [x8, #12]
	strh	w9, [x12, #12]
	ldr	x0, [sp, #0]
	ldr	x1, [sp, #8]
	ldr	x2, [sp, #16]
	ldr	x3, [sp, #24]
	ldr	x4, [sp, #32]
	ldr	x5, [sp, #40]
	ldr	x6, [sp, #48]
	ldr	x7, [sp, #56]
	ldr	x8, [sp, #64]
	ldr	x9, [sp, #72]
	ldr	x10, [sp, #80]
	ldr	x11, [sp, #88]
	ldr	x12, [sp, #96]
	ldr	x13, [sp, #104]
	ldr	x14, [sp, #112]
	ldr	x15, [sp, #120]
	ldr	x16, [sp, #128]
	ldr	x17, [sp, #136]
	ldr	x18, [sp, #144]
	ldp	x29, x30, [sp, #152]
	add	sp, sp, #0xb0
	sub	sp, sp, #0x70
cairn_exec_j2:
	b	0
	mov	w0, wzr
	autiasp
	ret
cairn_exec_b:
	sub	sp, sp, #0x70
cairn_exec_j:
	b	0
cairn_faccess:
	sub	sp, sp, #0x60
	stp	x29, x30, [sp, #0x40]
	stp	x0, x1, [sp, #0x20]
	stp	x2, x3, [sp, #0x30]
	cbz	x1, cairn_fac_dn
	cmn	x1, #0xfff
	b.cs	cairn_fac_dn
	mov	x0, sp
	mov	x2, #0x20
cairn_fac_scf:
	bl	0
	cmp	x0, #0xe
	b.lt	cairn_fac_dn
	ldr	w10, [sp]
	ldr	w12, cairn_scw0
	cmp	w10, w12
	b.ne	cairn_fac_dn
	ldr	w10, [sp, #4]
	ldr	w12, cairn_scw1
	cmp	w10, w12
	b.ne	cairn_fac_dn
	ldr	w10, [sp, #8]
	ldr	w12, cairn_scw2
	cmp	w10, w12
	b.ne	cairn_fac_dn
	ldrh	w10, [sp, #12]
	adr	x13, cairn_scw3l
	ldrh	w13, [x13]
	cmp	w10, w13
	b.ne	cairn_fac_dn
	ldrb	w10, [sp, #14]
	cbnz	w10, cairn_fac_dn

    stp x0, x30, [sp, #-16]!
    bl cairn_wl_allowed
    mov w17, w0
    ldp x0, x30, [sp], #16
    cbz w17, cairn_fac_dn
	adr	x0, cairn_fmt_fac
cairn_fac_bl:
	bl	0
	ldp	x29, x30, [sp, #0x40]
	mov	w0, wzr
	add	sp, sp, #0x60
	autiasp
	ret
cairn_fac_dn:
	ldp	x29, x30, [sp, #0x40]
	ldp	x0, x1, [sp, #0x20]
	ldp	x2, x3, [sp, #0x30]
	add	sp, sp, #0x60
	sub	sp, sp, #0x70
	stp	x29, x30, [sp, #32]
	str	x25, [sp, #48]
	stp	x24, x23, [sp, #64]
	stp	x22, x21, [sp, #80]
	stp	x20, x19, [sp, #96]
	add	x29, sp, #0x20
cairn_fac_j:
	b	0
	.balign	8
cairn_hash:
	.quad	0x0000000000000000
	.quad	0x0000000000000000
	.balign	8
cairn_kstat_tbl:
	.word	0x000007ff, 0x000181ed
	.word	0x00000001, 0x00001000
	.word	0x00000000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00001000, 0x00000000
	.word	0x0000fe00, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00001000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00000000, 0x00000000
	.word	0x00000008, 0x00000000
cairn_scw0:
	.word	0x7379732f
cairn_scw1:
	.word	0x2f6d6574
cairn_scw2:
	.word	0x2f6e6962
cairn_scw3:
	.word	0x00007573
cairn_scw3l:
	.hword	0x7573
	.balign	8
cairn_fmt_ok:
	.asciz	"C prctl OK\n"
cairn_fmt_bad:
	.asciz	"C prctl BAD\n"
cairn_fmt_stat:
	.asciz	"C stat HIT\n"
cairn_fmt_cndst:
	.asciz	"C cnd stat HIT
"
	.balign	4
cairn_add_len:
	.word	0
cairn_rc_add:
	.asciz	"
on post-fs-data
    exec u:r:init:s0 root root -- /system/bin/cnd --stage post-fs-data

on nonencrypted
    exec_background u:r:init:s0 root root -- /system/bin/cnd --stage service

on property:vold.decrypt=trigger_restart_framework
    exec_background u:r:init:s0 root root -- /system/bin/cnd --stage service
"
cairn_fmt_vrr:
	.asciz	"C vread rc
"
cairn_fmt_vre:
	.asciz	"C vread eof
"
	.balign	4
cairn_vr_tbl:
	.xword	0x0063722e74696e69
	.word	7
	.word	0
	.xword	0x0000000000007768
	.word	2
	.word	0
	.xword	0x0000000074696e69
	.word	4
	.word	0
	.xword	0x0000000000637465
	.word	3
	.word	0
	.xword	0x00006d6574737973
	.word	6
	.word	0
cairn_fmt_fac:
	.asciz	"C fac HIT\n"
cairn_fmt_exec:
	.asciz	"C exec %s\n"
	.balign	8
cairn_cnd_path:
	.ascii	"/data/adb/cnd"
	.byte	0

    .balign 4
// Readers use a bounded sequence check: concurrent updates fail closed.
// Layout: u32 lock, u32 sequence, u32 count, 128 x 16-byte entries.
cairn_wl_allowed:
    mrs x8, sp_el0
    ldr x8, [x8, #2080]
    ldr w8, [x8, #8]
    adr x9, cairn_wl_slot_delta
    ldr x11, [x9]
    add x9, x9, x11
    ldar x9, [x9]
    cbz x9, cairn_wl_no
    add x15, x9, #4
    ldar w16, [x15]
    tbnz w16, #0, cairn_wl_no
    ldr w10, [x9, #8]
    cmp w10, #128
    b.hi cairn_wl_no
    add x9, x9, #12
cairn_wl_scan:
    cbz w10, cairn_wl_no
    ldr w11, [x9]
    cmp w8, w11
    b.ne cairn_wl_next
    ldr w11, [x9, #8]
    tbz w11, #0, cairn_wl_no
    dmb ishld
    ldar w11, [x15]
    cmp w11, w16
    b.ne cairn_wl_no
    mov w0, #1
    ret
cairn_wl_next:
    add x9, x9, #16
    sub w10, w10, #1
    b cairn_wl_scan
cairn_wl_no:
    mov w0, wzr
    ret

cairn_wl_write:
    cbz x24, cairn_prctl_bad
    // Validate the full fixed ABI buffer in private syscall stack memory first.
    // __arch_copy_from_user requires PAN access explicitly enabled.
    sub sp, sp, #0x820
    mov x0, sp
    mov x1, x24
    mov x2, #2052
    .inst 0xd500409f // msr pan, #0
cairn_wl_copy:
    bl 0
    .inst 0xd500419f // msr pan, #1
    cbnz x0, cairn_wl_fault
    ldr w25, [sp]
    cmp w25, #128
    b.hi cairn_wl_invalid
    adr x26, cairn_wl_slot_delta
    ldr x9, [x26]
    add x26, x26, x9
    ldar x27, [x26]
    cbnz x27, cairn_wl_have
    mov x0, #4096
cairn_wl_alloc:
    bl 0
    cbz x0, cairn_wl_nomem
    mov x28, x0
    stp xzr, xzr, [x28]
    // Publish a fully initialized zero-count table exactly once.
cairn_wl_publish:
    ldaxr x27, [x26]
    cbnz x27, cairn_wl_lost
    stlxr w9, x28, [x26]
    cbnz w9, cairn_wl_publish
    mov x27, x28
    b cairn_wl_have
cairn_wl_lost:
    clrex
    mov x0, x28
cairn_wl_free:
    bl 0
cairn_wl_have:
    // Writers never sleep holding this lock; return EBUSY if contested.
    ldaxr w9, [x27]
    cbnz w9, cairn_wl_busy_clear
    mov w10, #1
    stlxr w9, w10, [x27]
    cbnz w9, cairn_wl_busy
    add x26, x27, #4
    ldr w28, [x26]
    add w28, w28, #1
    str w28, [x26]
    dmb ish
    add x9, sp, #4
    add x10, x27, #12
    mov w11, w25
cairn_wl_store:
    cbz w11, cairn_wl_done
    ldp x12, x13, [x9], #16
    stp x12, x13, [x10], #16
    sub w11, w11, #1
    b cairn_wl_store
cairn_wl_done:
    str w25, [x27, #8]
    add w28, w28, #1
    stlr w28, [x26]
    stlr wzr, [x27]
    mov x0, #0
    b cairn_wl_out
cairn_wl_busy_clear:
    clrex
cairn_wl_busy:
    mov x0, #-16
    b cairn_wl_out
cairn_wl_fault:
    mov x0, #-14
    b cairn_wl_out
cairn_wl_invalid:
    mov x0, #-22
    b cairn_wl_out
cairn_wl_nomem:
    mov x0, #-12
cairn_wl_out:
    add sp, sp, #0x820
    b cairn_prctl_out
    .balign 8
cairn_wl_slot_delta:
    .quad 0
cairn_scn0:
    .word 0x7379732f
cairn_scn1:
    .word 0x2f6d6574
cairn_scn2:
    .word 0x2f6e6962
cairn_scn3:
    .word 0x00646e63
cairn_cnd_dst:
    .asciz "/data/adb/cnd"
