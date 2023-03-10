/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Generated mock file from original source file
 *   Functions generated:23
 */

#include <map>
#include <string>

#include "bt_target.h"
#include "bta/av/bta_av_int.h"
#include "osi/include/allocator.h"
#include "osi/include/compat.h"
#include "osi/include/log.h"
#include "stack/include/bt_hdr.h"
#include "test/common/mock_functions.h"
#include "types/raw_address.h"

#ifndef UNUSED_ATTR
#define UNUSED_ATTR
#endif

void BTA_AvEnable(tBTA_AV_FEAT features, tBTA_AV_CBACK* p_cback) {
  inc_func_call_count(__func__);
}
void BTA_AvClose(tBTA_AV_HNDL handle) { inc_func_call_count(__func__); }
void BTA_AvCloseRc(uint8_t rc_handle) { inc_func_call_count(__func__); }
void BTA_AvDeregister(tBTA_AV_HNDL hndl) { inc_func_call_count(__func__); }
void BTA_AvDisable(void) { inc_func_call_count(__func__); }
void BTA_AvDisconnect(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
}
void BTA_AvMetaCmd(uint8_t rc_handle, uint8_t label, tBTA_AV_CMD cmd_code,
                   BT_HDR* p_pkt) {
  inc_func_call_count(__func__);
}
void BTA_AvMetaRsp(uint8_t rc_handle, uint8_t label, tBTA_AV_CODE rsp_code,
                   BT_HDR* p_pkt) {
  inc_func_call_count(__func__);
}
void BTA_AvOffloadStart(tBTA_AV_HNDL hndl) { inc_func_call_count(__func__); }
void BTA_AvOffloadStartRsp(tBTA_AV_HNDL hndl, tBTA_AV_STATUS status) {
  inc_func_call_count(__func__);
}
void BTA_AvOpen(const RawAddress& bd_addr, tBTA_AV_HNDL handle, bool use_rc,
                uint16_t uuid) {
  inc_func_call_count(__func__);
}
void BTA_AvOpenRc(tBTA_AV_HNDL handle) { inc_func_call_count(__func__); }
void BTA_AvProtectReq(tBTA_AV_HNDL hndl, uint8_t* p_data, uint16_t len) {
  inc_func_call_count(__func__);
}
void BTA_AvProtectRsp(tBTA_AV_HNDL hndl, uint8_t error_code, uint8_t* p_data,
                      uint16_t len) {
  inc_func_call_count(__func__);
}
void BTA_AvReconfig(tBTA_AV_HNDL hndl, bool suspend, uint8_t sep_info_idx,
                    uint8_t* p_codec_info, uint8_t num_protect,
                    const uint8_t* p_protect_info) {
  inc_func_call_count(__func__);
}
void BTA_AvRegister(tBTA_AV_CHNL chnl, const char* p_service_name,
                    uint8_t app_id, tBTA_AV_SINK_DATA_CBACK* p_sink_data_cback,
                    uint16_t service_uuid) {
  inc_func_call_count(__func__);
}
void BTA_AvRemoteCmd(uint8_t rc_handle, uint8_t label, tBTA_AV_RC rc_id,
                     tBTA_AV_STATE key_state) {
  inc_func_call_count(__func__);
}
void BTA_AvRemoteVendorUniqueCmd(uint8_t rc_handle, uint8_t label,
                                 tBTA_AV_STATE key_state, uint8_t* p_msg,
                                 uint8_t buf_len) {
  inc_func_call_count(__func__);
}
void BTA_AvStart(tBTA_AV_HNDL handle, bool use_latency_mode) {
  inc_func_call_count(__func__);
}
void BTA_AvStop(tBTA_AV_HNDL handle, bool suspend) {
  inc_func_call_count(__func__);
}
void BTA_AvVendorCmd(uint8_t rc_handle, uint8_t label, tBTA_AV_CODE cmd_code,
                     uint8_t* p_data, uint16_t len) {
  inc_func_call_count(__func__);
}
void BTA_AvVendorRsp(uint8_t rc_handle, uint8_t label, tBTA_AV_CODE rsp_code,
                     uint8_t* p_data, uint16_t len, uint32_t company_id) {
  inc_func_call_count(__func__);
}
void BTA_AvSetLatency(tBTA_AV_HNDL handle, bool is_low_latency) {
  inc_func_call_count(__func__);
}
