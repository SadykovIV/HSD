#include "hks_client.h"
#include "hks_errno.h"
#include "huks_adapter.h"

static int calls;

/* A key may be removed between looking it up and reading its attributes. */
int32_t hks_get_key_param(const struct hks_blob *alias, struct hks_key_param *parameters)
{
    (void)alias;
    (void)parameters;
    calls++;
    return HKS_ERROR_KEY_NOT_EXIST;
}

int main(void)
{
    struct hc_key_alias alias = {.length = 1, .key_alias = {1}};
    struct huks_key_type type = {0};
    struct hc_auth_id id = {0};
    return get_lt_key_info(&alias, &type, &id) == HKS_ERROR_KEY_NOT_EXIST && calls == 1 ? 0 : 1;
}
